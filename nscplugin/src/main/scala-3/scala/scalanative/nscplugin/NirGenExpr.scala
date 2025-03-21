package scala.scalanative.nscplugin

import scala.language.implicitConversions
import scala.annotation._

import dotty.tools.dotc.ast
import ast.tpd._
import ast.TreeInfo._
import dotty.tools.backend.ScalaPrimitivesOps._
import dotty.tools.dotc.core
import core.Contexts._
import core.Symbols._
import core.Names._
import core.Types._
import core.Constants._
import core.StdNames._
import core.Flags._
import core.Denotations._
import core.SymDenotations._
import core.TypeErasure.ErasedValueType
import core._
import dotty.tools.FatalError
import dotty.tools.dotc.report
import dotty.tools.dotc.transform
import transform.SymUtils._
import transform.{ValueClasses, Erasure}
import dotty.tools.backend.jvm.DottyBackendInterface.symExtensions

import scala.collection.mutable
import scala.scalanative.nir
import nir._
import scala.scalanative.util.ScopedVar.scoped
import scala.scalanative.util.unsupported
import scala.scalanative.util.StringUtils
import dotty.tools.dotc.ast.desugar

trait NirGenExpr(using Context) {
  self: NirCodeGen =>
  import positionsConversions.fromSpan

  sealed case class ValTree(value: nir.Val) extends Tree
  sealed case class ContTree(f: () => nir.Val) extends Tree

  class ExprBuffer(using fresh: Fresh) extends FixupBuffer {
    buf =>
    def genExpr(tree: Tree): Val = {
      tree match {
        case EmptyTree      => Val.Unit
        case ValTree(value) => value
        case ContTree(f)    => f()
        case tree: Apply =>
          val updatedTree = lazyValsAdapter.transformApply(tree)
          genApply(updatedTree)
        case tree: Assign         => genAssign(tree)
        case tree: Block          => genBlock(tree)
        case tree: Closure        => genClosure(tree)
        case tree: Labeled        => genLabelDef(tree)
        case tree: Ident          => genIdent(tree)
        case tree: If             => genIf(tree)
        case tree: JavaSeqLiteral => genJavaSeqLiteral(tree)
        case tree: Literal        => genLiteral(tree)
        case tree: Match          => genMatch(tree)
        case tree: Return         => genReturn(tree)
        case tree: Select         => genSelect(tree)
        case tree: This           => genThis(tree)
        case tree: Try            => genTry(tree)
        case tree: Typed          => genTyped(tree)
        case tree: TypeApply      => genTypeApply(tree)
        case tree: ValDef         => genValDef(tree)
        case tree: WhileDo        => genWhileDo(tree)
        case _ =>
          throw FatalError(
            s"""Unexpected tree in genExpr: `${tree}`
             raw=$tree
             pos=${tree.span}
             """
          )
      }
    }

    def genApply(app: Apply): Val = {
      given nir.Position = app.span
      val Apply(fun, args) = app

      val sym = fun.symbol
      def isStatic = sym.owner.isStaticOwner
      def qualifier = qualifierOf(fun)

      fun match {
        case _: TypeApply => genApplyTypeApply(app)
        case Select(Super(_, _), _) =>
          genApplyMethod(
            sym,
            statically = true,
            curMethodThis.get.get,
            args
          )
        case Select(New(_), nme.CONSTRUCTOR) =>
          genApplyNew(app)
        case _ if sym == defn.newArrayMethod =>
          val Seq(
            Literal(componentType: Constant),
            arrayType,
            SeqLiteral(dimensions, _)
          ) = args
          if (dimensions.size == 1)
            val length = genExpr(dimensions.head)
            buf.arrayalloc(genType(componentType.typeValue), length, unwind)
          else genApplyMethod(sym, statically = isStatic, qualifier, args)
        case _ =>
          if (nirPrimitives.isPrimitive(fun)) genApplyPrimitive(app)
          else if (Erasure.Boxing.isBox(sym))
            val arg = args.head
            genApplyBox(arg.tpe, arg)
          else if (Erasure.Boxing.isUnbox(sym))
            genApplyUnbox(app.tpe, args.head)
          else genApplyMethod(sym, statically = isStatic, qualifier, args)
      }
    }

    def genAssign(tree: Assign): Val = {
      val Assign(lhsp, rhsp) = tree
      given nir.Position = tree.span

      desugarTree(lhsp) match {
        case sel @ Select(qualp, _) =>
          def rhs = genExpr(rhsp)
          val sym = sel.symbol
          val name = genFieldName(sym)
          if (sym.isExtern) {
            // Ignore intrinsic call to extern in class constructor
            // It would always present and would lead to undefined behaviour otherwise
            val shouldIgnoreAssign =
              curMethodSym.get.isClassConstructor &&
                rhsp.symbol == defnNir.UnsafePackage_extern
            if (shouldIgnoreAssign) Val.Unit
            else {
              val externTy = genExternType(sel.tpe)
              genStoreExtern(externTy, sym, rhs)
            }
          } else {
            val qual =
              if (sym.isStaticMember) genModule(qualp.symbol)
              else genExpr(qualp)
            val ty = genType(sel.tpe)
            buf.fieldstore(ty, qual, name, rhs, unwind)
          }

        case id: Ident =>
          val rhs = genExpr(rhsp)
          val slot = curMethodEnv.resolve(id.symbol)
          buf.varstore(slot, rhs, unwind)
      }
    }

    def genBlock(block: Block): Val = {
      val Block(stats, last) = block

      def isCaseLabelDef(tree: Tree) =
        tree.isInstanceOf[Labeled] && tree.symbol.isAllOf(SyntheticCase)

      def translateMatch(last: Labeled) = {
        val (prologue, cases) = stats.span(!isCaseLabelDef(_))
        val labels = cases.asInstanceOf[List[Labeled]]
        genMatch(prologue, labels :+ last)
      }

      last match {
        case label: Labeled if isCaseLabelDef(label) =>
          translateMatch(label)

        case Apply(
              TypeApply(Select(label: Labeled, nme.asInstanceOf_), _),
              _
            ) if isCaseLabelDef(label) =>
          translateMatch(label)

        case _ =>
          stats.foreach(genExpr)
          genExpr(last)
      }
    }

    // Scala Native does not have any special treatment for closures.
    // We reimplement the anonymous class generation which was originally used on
    // Scala 2.11 and earlier.
    //
    // Each anonymous function gets a generated class for it,
    // similarly to closure encoding on Scala 2.11 and earlier:
    //
    //   class AnonFunX extends java.lang.Object with FunctionalInterface {
    //     var capture1: T1
    //     ...
    //     var captureN: TN
    //     def <init>(this, v1: T1, ..., vN: TN): Unit = {
    //       java.lang.Object.<init>(this)
    //       this.capture1 = v1
    //       ...
    //       this.captureN = vN
    //     }
    //     def samMethod(param1: Tp1, ..., paramK: TpK): Tret = {
    //       EnclsoingClass.this.staticMethod(param1, ..., paramK, this.capture1, ..., this.captureN)
    //     }
    //   }
    //
    // Bridges might require multiple samMethod variants to be created.
    def genClosure(tree: Closure): Val = {
      given nir.Position = tree.span
      val Closure(env, fun, functionalInterface) = tree
      val treeTpe = tree.tpe.typeSymbol
      val funSym = fun.symbol
      val funInterfaceSym = functionalInterface.tpe.typeSymbol
      val isFunction =
        !funInterfaceSym.exists || defn.isFunctionClass(funInterfaceSym)

      val anonClassName = {
        val Global.Top(className) = genTypeName(curClassSym)
        val suffix = "$$Lambda$" + curClassFresh.get.apply().id
        nir.Global.Top(className + suffix)
      }

      val isStaticCall = funSym.isStaticInNIR
      val allCaptureValues =
        if (isStaticCall) env
        else qualifierOf(fun) :: env
      val captureSyms = allCaptureValues.map(_.symbol)
      val captureTypesAndNames = {
        for
          (tree, idx) <- allCaptureValues.zipWithIndex
          tpe = tree match {
            case This(iden) => genType(curClassSym.get)
            case _          => genType(tree.tpe)
          }
          name = anonClassName.member(nir.Sig.Field(s"capture$idx"))
        yield (tpe, name)
      }
      val (captureTypes, captureNames) = captureTypesAndNames.unzip

      def genAnonymousClass: nir.Defn = {
        val traits =
          if (functionalInterface.isEmpty) genTypeName(treeTpe) :: Nil
          else {
            val parents = funInterfaceSym.info.parents
            genTypeName(funInterfaceSym) +: parents.collect {
              case tpe if tpe.typeSymbol.isTraitOrInterface =>
                genTypeName(tpe.typeSymbol)
            }
          }

        nir.Defn.Class(
          attrs = Attrs.None,
          name = anonClassName,
          parent = Some(nir.Rt.Object.name),
          traits = traits
        )
      }

      def genCaptureFields: List[nir.Defn] = {
        for (tpe, name) <- captureTypesAndNames
        yield nir.Defn.Var(
          attrs = Attrs.None,
          name = name,
          ty = tpe,
          rhs = Val.Zero(tpe)
        )
      }

      val ctorName = anonClassName.member(Sig.Ctor(captureTypes))
      val ctorTy = nir.Type.Function(
        Type.Ref(anonClassName) +: captureTypes,
        Type.Unit
      )

      def genAnonymousClassCtor: nir.Defn = {
        val body = {
          val fresh = Fresh()
          val buf = new nir.Buffer()(fresh)

          val superTy = nir.Type.Function(Seq(Rt.Object), Type.Unit)
          val superName = Rt.Object.name.member(Sig.Ctor(Seq()))
          val superCtor = Val.Global(superName, Type.Ptr)

          val self = Val.Local(fresh(), Type.Ref(anonClassName))
          val captureFormals = captureTypes.map(Val.Local(fresh(), _))
          buf.label(fresh(), self +: captureFormals)
          buf.call(superTy, superCtor, Seq(self), Next.None)
          captureNames.zip(captureFormals).foreach { (name, capture) =>
            buf.fieldstore(capture.ty, self, name, capture, Next.None)
          }
          buf.ret(Val.Unit)

          buf.toSeq
        }

        nir.Defn.Define(Attrs.None, ctorName, ctorTy, body)
      }

      def resolveAnonClassMethods: List[Symbol] = {
        val samMethods =
          if (funInterfaceSym.exists) funInterfaceSym.info.possibleSamMethods
          else treeTpe.info.possibleSamMethods

        samMethods
          .map(_.symbol)
          .toList
      }

      def genAnonClassMethod(sym: Symbol): nir.Defn = {
        val Global.Member(_, funSig) = genName(sym)
        val Sig.Method(_, sigTypes :+ retType, _) = funSig.unmangled

        val selfType = Type.Ref(anonClassName)
        val methodName = anonClassName.member(funSig)
        val paramTypes = selfType +: sigTypes
        val paramSyms = funSym.paramSymss.flatten

        def genBody = {
          given fresh: Fresh = Fresh()
          given buf: ExprBuffer = new ExprBuffer()
          scoped(
            curFresh := fresh,
            curExprBuffer := buf,
            curMethodEnv := MethodEnv(fresh),
            curMethodLabels := MethodLabelsEnv(fresh),
            curMethodInfo := CollectMethodInfo(),
            curUnwindHandler := None
          ) {
            val self = Val.Local(fresh(), selfType)
            val params = sigTypes.map(Val.Local(fresh(), _))

            buf.label(fresh(), self +: params)
            // At this point, the type parameter symbols are all Objects.
            // We need to transform them, so that their type conforms to
            // what the apply method expects:
            // - values that can be unboxed, are unboxed
            // - otherwise, the value is cast to the appropriate type
            val paramVals =
              for (param, sym) <- params.zip(paramSyms)
              yield ensureUnboxed(param, sym.info.finalResultType)

            val captureVals =
              for (sym, (tpe, name)) <- captureSyms.zip(captureTypesAndNames)
              yield buf.fieldload(tpe, self, name, unwind)

            val allVals = (captureVals ++ paramVals).toList.map(ValTree(_))
            val res = if (isStaticCall) {
              scoped(curMethodThis := None) {
                buf.genApplyStaticMethod(
                  funSym,
                  qualifierOf(fun).symbol,
                  allVals
                )
              }
            } else {
              val thisVal :: argVals = allVals
              scoped(curMethodThis := Some(thisVal.value)) {
                buf.genApplyMethod(
                  funSym,
                  statically = false,
                  thisVal,
                  argVals
                )
              }
            }

            val retValue =
              if (retType == res.ty) res
              else
                // Get the result type of the lambda after erasure, when entering posterasure.
                // This allows to recover the correct type in case value classes are involved.
                // In that case, the type will be an ErasedValueType.
                buf.ensureBoxed(res, funSym.info.finalResultType)
            buf.ret(retValue)
            buf.toSeq
          }
        }

        nir.Defn.Define(
          Attrs.None,
          methodName,
          Type.Function(paramTypes, retType),
          genBody
        )
      }

      def genAnonymousClassMethods: List[nir.Defn] =
        resolveAnonClassMethods
          .map(genAnonClassMethod)

      generatedDefns ++= {
        genAnonymousClass ::
          genAnonymousClassCtor ::
          genCaptureFields :::
          genAnonymousClassMethods
      }

      def allocateClosure() = {
        val alloc = buf.classalloc(anonClassName, unwind)
        val captures = allCaptureValues.map(genExpr)
        buf.call(
          ctorTy,
          Val.Global(ctorName, Type.Ptr),
          alloc +: captures,
          unwind
        )
        alloc
      }
      allocateClosure()
    }

    def genIdent(tree: Ident): Val =
      desugarIdent(tree) match {
        case Ident(_) =>
          val sym = tree.symbol
          given nir.Position = tree.span
          if (curMethodInfo.mutableVars.contains(sym))
            buf.varload(curMethodEnv.resolve(sym), unwind)
          else if (sym.is(Module))
            genModule(sym)
          else curMethodEnv.resolve(sym)
        case desuaged: Select =>
          genSelect(desuaged)
        case tree =>
          throw FatalError(s"Unsupported desugared ident tree: $tree")
      }

    def genIf(tree: If): Val = {
      given nir.Position = tree.span
      val If(cond, thenp, elsep) = tree
      val retty = genType(tree.tpe)
      genIf(retty, cond, thenp, elsep)
    }

    private def genIf(retty: nir.Type, condp: Tree, thenp: Tree, elsep: Tree)(
        using nir.Position
    ): Val = {
      val thenn, elsen, mergen = fresh()
      val mergev = Val.Local(fresh(), retty)

      locally {
        given nir.Position = condp.span
        getLinktimeCondition(condp) match {
          case Some(cond) =>
            buf.branchLinktime(cond, Next(thenn), Next(elsen))
          case None =>
            val cond = genExpr(condp)
            buf.branch(cond, Next(thenn), Next(elsen))(using condp.span)
        }
      }

      locally {
        given nir.Position = thenp.span
        buf.label(thenn)
        val thenv = genExpr(thenp)
        buf.jump(mergen, Seq(thenv))
      }
      locally {
        given nir.Position = elsep.span
        buf.label(elsen)
        val elsev = genExpr(elsep)
        buf.jump(mergen, Seq(elsev))
      }
      buf.label(mergen, Seq(mergev))
      mergev
    }

    def genJavaSeqLiteral(tree: JavaSeqLiteral): Val = {
      val JavaArrayType(elemTpe) = tree.tpe
      val arrayLength = Val.Int(tree.elems.length)

      val elems = tree.elems
      val elemty = genType(elemTpe)
      val values = genSimpleArgs(elems)
      given nir.Position = tree.span

      if (values.forall(_.isCanonical) && values.exists(v => !v.isZero))
        buf.arrayalloc(elemty, Val.ArrayValue(elemty, values), unwind)
      else
        val alloc = buf.arrayalloc(elemty, Val.Int(values.length), unwind)
        for (v, i) <- values.zipWithIndex if !v.isZero do
          given nir.Position = elems(i).span
          buf.arraystore(elemty, alloc, Val.Int(i), v, unwind)
        alloc
    }

    def genLabelDef(label: Labeled): Val = {
      given nir.Position = label.span
      val Labeled(bind, body) = label
      assert(bind.body == EmptyTree, "non-empty Labeled bind body")

      val (labelEntry, labelExit) = curMethodLabels.enterLabel(label)
      val labelExitParam = Val.Local(fresh(), genType(bind.tpe))

      buf.jump(Next(labelEntry))

      buf.label(labelEntry, Nil)
      buf.jump(labelExit, Seq(genExpr(label.expr)))

      buf.label(labelExit, Seq(labelExitParam))
      labelExitParam
    }

    def genLiteral(lit: Literal): Val = {
      given nir.Position = lit.span
      val value = lit.const

      value.tag match {
        case ClazzTag => genTypeValue(value.typeValue)
        case _        => genLiteralValue(lit)
      }
    }

    private def genLiteralValue(lit: Literal): Val = {
      val value = lit.const
      value.tag match {
        case UnitTag    => Val.Unit
        case NullTag    => Val.Null
        case BooleanTag => if (value.booleanValue) Val.True else Val.False
        case ByteTag    => Val.Byte(value.intValue.toByte)
        case ShortTag   => Val.Short(value.intValue.toShort)
        case CharTag    => Val.Char(value.intValue.toChar)
        case IntTag     => Val.Int(value.intValue)
        case LongTag    => Val.Long(value.longValue)
        case FloatTag   => Val.Float(value.floatValue)
        case DoubleTag  => Val.Double(value.doubleValue)
        case StringTag  => Val.String(value.stringValue)
      }
    }

    def genMatch(m: Match): Val = {
      given nir.Position = m.span
      val Match(scrutp, allcaseps) = m
      case class Case(
          name: Local,
          value: Val,
          tree: Tree,
          position: nir.Position
      )

      // Extract switch cases and assign unique names to them.
      val caseps: Seq[Case] = allcaseps.flatMap {
        case CaseDef(Ident(nme.WILDCARD), _, _) =>
          Seq()
        case cd @ CaseDef(pat, guard, body) =>
          assert(guard.isEmpty, "CaseDef guard was not empty")
          val vals: Seq[Val] = pat match {
            case lit: Literal =>
              List(genLiteralValue(lit))
            case Alternative(alts) =>
              alts.map {
                case lit: Literal => genLiteralValue(lit)
              }
            case _ =>
              Nil
          }
          vals.map(Case(fresh(), _, body, cd.span: nir.Position))
      }

      // Extract default case.
      val defaultp: Tree = allcaseps.collectFirst {
        case c @ CaseDef(Ident(nme.WILDCARD), _, body) => body
      }.get

      val retty = genType(m.tpe)
      val scrut = genExpr(scrutp)

      // Generate code for the switch and its cases.
      def genSwitch(): Val = {
        // Generate some more fresh names and types.
        val casenexts = caseps.map { case Case(n, v, _, _) => Next.Case(v, n) }
        val defaultnext = Next(fresh())
        val merge = fresh()
        val mergev = Val.Local(fresh(), retty)

        val defaultCasePos: nir.Position = defaultp.span

        // Generate code for the switch and its cases.
        val scrut = genExpr(scrutp)
        buf.switch(scrut, defaultnext, casenexts)
        buf.label(defaultnext.name)(using defaultCasePos)
        buf.jump(merge, Seq(genExpr(defaultp)))(using defaultCasePos)
        caseps.foreach {
          case Case(n, _, expr, pos) =>
            given nir.Position = pos
            buf.label(n)
            val caseres = genExpr(expr)
            buf.jump(merge, Seq(caseres))
        }
        buf.label(merge, Seq(mergev))
        mergev
      }

      def genIfsChain(): Val = {
        /* Default label needs to be generated before any others and then added to
         * current MethodEnv. It's label might be referenced in any of them in
         * case of match with guards, eg.:
         *
         * "Hello, World!" match {
         *  case "Hello" if cond1 => "foo"
         *  case "World" if cond2 => "bar"
         *  case _ if cond3 => "bar-baz"
         *  case _ => "baz-bar"
         * }
         *
         * might be translated to something like:
         *
         * val x1 = "Hello, World!"
         * if(x1 == "Hello"){ if(cond1) "foo" else default4() }
         * else if (x1 == "World"){ if(cond2) "bar" else default4() }
         * else default4()
         *
         * def default4() = if(cond3) "bar-baz" else "baz-bar
         *
         * We need to make sure to only generate LabelDef at this stage.
         * Generating other ASTs and mutating state might lead to unexpected
         * runtime errors.
         */
        val optDefaultLabel = defaultp match {
          case label: Labeled => Some(genLabelDef(label))
          case _              => None
        }

        def loop(cases: List[Case]): Val = {
          cases match {
            case Case(_, caze, body, p) :: elsep =>
              given nir.Position = p

              val cond =
                buf.genClassEquality(
                  leftp = ValTree(scrut),
                  rightp = ValTree(caze),
                  ref = false,
                  negated = false
                )
              buf.genIf(
                retty = retty,
                condp = ValTree(cond),
                thenp = ContTree(() => genExpr(body)),
                elsep = ContTree(() => loop(elsep))
              )

            case Nil => optDefaultLabel.getOrElse(genExpr(defaultp))
          }
        }
        loop(caseps.toList)
      }

      /* Since 2.13 we need to enforce that only Int switch cases reach backend
       * For all other cases we're generating If-else chain */
      val isIntMatch = scrut.ty == Type.Int &&
        caseps.forall(_._2.ty == Type.Int)

      if (isIntMatch) genSwitch()
      else genIfsChain()
    }

    private def genMatch(prologue: List[Tree], lds: List[Labeled]): Val = {
      // Generate prologue expressions.
      prologue.foreach(genExpr(_))

      // Enter symbols for all labels and jump to the first one.
      lds.foreach(curMethodLabels.enterLabel)
      val firstLd = lds.head
      given nir.Position = firstLd.span
      buf.jump(Next(curMethodLabels.resolveEntry(firstLd)))

      // Generate code for all labels and return value of the last one.
      lds.map(genLabelDef(_)).last
    }

    def genModule(sym: Symbol)(using nir.Position): Val = {
      val moduleSym = if (sym.isTerm) sym.moduleClass else sym
      val name = genModuleName(moduleSym)
      buf.module(name, unwind)
    }

    def genReturn(tree: Return): Val = {
      val Return(exprp, from) = tree
      given nir.Position = tree.span
      val rhs = genExpr(exprp)
      val fromSym = from.symbol
      val label = Option.when(fromSym.is(Label)) {
        curMethodLabels.resolveExit(fromSym)
      }
      genReturn(rhs, label)
    }

    def genReturn(value: Val, from: Option[Local] = None)(using
        pos: nir.Position
    ): Val = {
      val retv =
        if (curMethodIsExtern.get)
          val Type.Function(_, retty) = genExternMethodSig(curMethodSym)
          toExtern(retty, value)
        else value

      from match {
        case Some(label) => buf.jump(label, Seq(retv))
        case _           => buf.ret(retv)
      }
      Val.Unit
    }

    def genSelect(tree: Select): Val = {
      given nir.Position = tree.span
      val Select(qualp, selp) = tree

      val sym = tree.symbol
      val owner = sym.owner

      if (sym.is(Module)) genModule(sym)
      else if (sym.isStaticInNIR && !sym.isExtern)
        genStaticMember(sym, qualp.symbol)
      else if (sym.is(Method))
        genApplyMethod(sym, statically = false, qualp, Seq())
      else if (owner.isStruct) {
        val index = owner.info.decls.filter(_.isField).toList.indexOf(sym)
        val qual = genExpr(qualp)
        buf.extract(qual, Seq(index), unwind)
      } else {
        val ty = genType(tree.tpe)
        val qual =
          if (sym.isStaticMember) genModule(owner)
          else genExpr(qualp)
        val name = genFieldName(tree.symbol)
        if (sym.isExtern) {
          val externTy = genExternType(tree.tpe)
          genLoadExtern(ty, externTy, tree.symbol)
        } else {
          buf.fieldload(ty, qual, name, unwind)
        }
      }
    }

    def genThis(tree: This): Val = {
      given nir.Position = tree.span
      val sym = tree.symbol
      def currentThis = curMethodThis.get
      def currentClass = curClassSym.get
      def currentMethod = curMethodSym.get

      def canUseCurrentThis = currentThis.nonEmpty &&
        (sym == currentClass || currentMethod.owner == currentClass)
      val canLoadAsModule =
        sym != currentClass &&
          sym.is(ModuleClass, butNot = Package) ||
          sym.isPackageObject

      if (canLoadAsModule) genModule(sym)
      else if (canUseCurrentThis) currentThis.get
      else
        report.error(
          s"Cannot resolve `this` instance for ${tree}",
          tree.sourcePos
        )
        Val.Zero(genType(currentClass))
    }

    def genTry(tree: Try): Val = tree match {
      case Try(expr, catches, finalizer)
          if catches.isEmpty && finalizer.isEmpty =>
        genExpr(expr)
      case Try(expr, catches, finalizer) =>
        val retty = genType(tree.tpe)
        genTry(retty, expr, catches, finalizer)
    }

    private def genTry(
        retty: nir.Type,
        expr: Tree,
        catches: List[Tree],
        finallyp: Tree
    ): Val = {
      given nir.Position = expr.span
      val handler, excn, normaln, mergen = fresh()
      val excv = Val.Local(fresh(), Rt.Object)
      val mergev = Val.Local(fresh(), retty)

      // Nested code gen to separate out try/catch-related instructions.
      val nested = ExprBuffer()
      scoped(curUnwindHandler := Some(handler)) {
        nested.label(normaln)
        val res = nested.genExpr(expr)
        nested.jump(mergen, Seq(res))
      }
      locally {
        nested.label(handler, Seq(excv))
        val res = nested.genTryCatch(retty, excv, mergen, catches)
        nested.jump(mergen, Seq(res))
      }

      // Append finally to the try/catch instructions and merge them back.
      val insts =
        if (finallyp.isEmpty) nested.toSeq
        else genTryFinally(finallyp, nested.toSeq)

      // Append try/catch instructions to the outher instruction buffer.
      buf.jump(Next(normaln))
      buf ++= insts
      buf.label(mergen, Seq(mergev))
      mergev
    }

    private def genTryCatch(
        retty: nir.Type,
        exc: Val,
        mergen: Local,
        catches: List[Tree]
    )(using exprPos: nir.Position): Val = {
      val cases = catches.map {
        case cd @ CaseDef(pat, _, body) =>
          val (excty, symopt) = pat match {
            case Typed(Ident(nme.WILDCARD), tpt) =>
              genType(tpt.tpe) -> None
            case Ident(nme.WILDCARD) =>
              genType(defn.ThrowableClass.info.resultType) -> None
            case Bind(_, _) =>
              genType(pat.tpe) -> Some(pat.symbol)
          }
          val f = { () =>
            symopt.foreach { sym =>
              val cast = buf.as(excty, exc, unwind)(cd.span)
              curMethodEnv.enter(sym, cast)
            }
            val res = genExpr(body)
            buf.jump(mergen, Seq(res))
            Val.Unit
          }
          (excty, f, exprPos)
      }

      def wrap(cases: Seq[(nir.Type, () => Val, nir.Position)]): Val =
        cases match {
          case Seq() =>
            buf.raise(exc, unwind)
            Val.Unit
          case (excty, f, pos) +: rest =>
            val cond = buf.is(excty, exc, unwind)(pos)
            genIf(
              retty,
              ValTree(cond),
              ContTree(f),
              ContTree(() => wrap(rest))
            )(using pos)
        }

      wrap(cases)
    }

    private def genTryFinally(
        finallyp: Tree,
        insts: Seq[nir.Inst]
    ): Seq[Inst] = {
      val labels =
        insts.collect {
          case Inst.Label(n, _) => n
        }.toSet
      def internal(cf: Inst.Cf) = cf match {
        case inst @ Inst.Jump(n) =>
          labels.contains(n.name)
        case inst @ Inst.If(_, n1, n2) =>
          labels.contains(n1.name) && labels.contains(n2.name)
        case inst @ Inst.Switch(_, n, ns) =>
          labels.contains(n.name) && ns.forall(n => labels.contains(n.name))
        case inst @ Inst.Throw(_, n) =>
          (n ne Next.None) && labels.contains(n.name)
        case _ =>
          false
      }

      val finalies = new ExprBuffer
      val transformed = insts.map {
        case cf: Inst.Cf if internal(cf) =>
          // We don't touch control-flow within try/catch block.
          cf
        case cf: Inst.Cf =>
          // All control-flow edges that jump outside the try/catch block
          // must first go through finally block if it's present. We generate
          // a new copy of the finally handler for every edge.
          val finallyn = fresh()
          finalies.label(finallyn)(cf.pos)
          val res = finalies.genExpr(finallyp)
          finalies += cf
          // The original jump outside goes through finally block first.
          Inst.Jump(Next(finallyn))(cf.pos)
        case inst =>
          inst
      }
      transformed ++ finalies.toSeq
    }

    def genTyped(tree: Typed): Val = tree match {
      case Typed(Super(_, _), _) => curMethodThis.get.get
      case Typed(expr, _)        => genExpr(expr)
    }

    def genTypeApply(tree: TypeApply): Val = {
      given nir.Position = tree.span
      val TypeApply(fun @ Select(receiverp, _), targs) = tree

      val funSym = fun.symbol
      val fromty = genType(receiverp.tpe)
      val toty = genType(targs.head.tpe)
      def boxty = genBoxType(targs.head.tpe)
      val value = genExpr(receiverp)
      def boxed = boxValue(receiverp.tpe, value)(using receiverp.span)

      if (funSym == defn.Any_isInstanceOf) buf.is(boxty, boxed, unwind)
      else if (funSym == defn.Any_asInstanceOf)
        (fromty, toty) match {
          case _ if boxed.ty == boxty =>
            boxed
          case (_: Type.PrimitiveKind, _: Type.PrimitiveKind) =>
            genCoercion(value, fromty, toty)
          case (_, Type.Nothing) =>
            val runtimeNothing = genType(defn.NothingClass)
            val isNullL, notNullL = fresh()
            val isNull = buf.comp(Comp.Ieq, boxed.ty, boxed, Val.Null, unwind)
            buf.branch(isNull, Next(isNullL), Next(notNullL))
            buf.label(isNullL)
            buf.raise(Val.Null, unwind)
            buf.label(notNullL)
            buf.as(runtimeNothing, boxed, unwind)
            buf.unreachable(unwind)
            buf.label(fresh())
            Val.Zero(Type.Nothing)
          case _ =>
            val cast = buf.as(boxty, boxed, unwind)
            unboxValue(tree.tpe, partial = true, cast)
        }
      else {
        report.error("Unkown case genTypeApply: " + funSym, tree.sourcePos)
        Val.Null
      }

    }

    def genValDef(vd: ValDef): Val = {
      given nir.Position = vd.span
      val rhs = genExpr(vd.rhs)
      val isMutable = curMethodInfo.mutableVars.contains(vd.symbol)
      if (vd.symbol.isExtern)
        checkExplicitReturnTypeAnnotation(vd, "extern field")
      if (isMutable)
        val slot = curMethodEnv.resolve(vd.symbol)
        buf.varstore(slot, rhs, unwind)
      else
        curMethodEnv.enter(vd.symbol, rhs)
        Val.Unit
    }

    def genWhileDo(wd: WhileDo): Val = {
      val WhileDo(cond, body) = wd
      val condLabel, bodyLabel, exitLabel = fresh()

      locally {
        given nir.Position = wd.span
        buf.jump(Next(condLabel))
      }

      locally {
        given nir.Position = cond.span
        buf.label(condLabel)
        val genCond =
          if (cond == EmptyTree) nir.Val.Bool(true)
          else genExpr(cond)
        buf.branch(genCond, Next(bodyLabel), Next(exitLabel))
      }

      locally {
        given nir.Position = body.span
        buf.label(bodyLabel)
        val _ = genExpr(body)
        buf.jump(condLabel, Nil)
      }

      locally {
        given nir.Position = wd.span.endPos
        buf.label(exitLabel, Seq())
        if (cond == EmptyTree) Val.Zero(genType(defn.NothingClass))
        else Val.Unit
      }
    }

    private def genApplyBox(st: SimpleType, argp: Tree): Val = {
      given nir.Position = argp.span
      val value = genExpr(argp)
      buf.box(genBoxType(st), value, unwind)
    }

    private def genApplyUnbox(st: SimpleType, argp: Tree)(using
        nir.Position
    ): Val = {
      val value = genExpr(argp)
      value.ty match {
        case _: scalanative.nir.Type.I | _: scalanative.nir.Type.F =>
          // No need for unboxing, fixing some slack generated by the general
          // purpose Scala compiler.
          value
        case _ =>
          buf.unbox(genBoxType(st), value, unwind)
      }
    }

    private def genApplyPrimitive(app: Apply): Val = {
      import NirPrimitives._
      import dotty.tools.backend.ScalaPrimitivesOps._
      given nir.Position = app.span
      val Apply(fun, args) = app
      val Select(receiver, _) = desugarTree(fun)

      val sym = app.symbol
      val code = nirPrimitives.getPrimitive(app, receiver.tpe)
      if (isArithmeticOp(code) || isLogicalOp(code) || isComparisonOp(code))
        genSimpleOp(app, receiver :: args, code)
      else if (code == THROW) genThrow(app, args)
      else if (code == CONCAT) genStringConcat(receiver, args.head)
      else if (code == HASH) genHashCode(args.head)
      else if (code == BOXED_UNIT) Val.Unit
      else if (code == SYNCHRONIZED) genSynchronized(receiver, args.head)
      else if (isArrayOp(code) || code == ARRAY_CLONE) genArrayOp(app, code)
      else if (isCoercion(code)) genCoercion(app, receiver, code)
      else if (NirPrimitives.isRawPtrOp(code)) genRawPtrOp(app, code)
      else if (NirPrimitives.isRawCastOp(code)) genRawCastOp(app, code)
      else if (NirPrimitives.isUnsignedOp(code)) genUnsignedOp(app, code)
      else if (code == CFUNCPTR_APPLY) genCFuncPtrApply(app)
      else if (code == CFUNCPTR_FROM_FUNCTION) genCFuncFromScalaFunction(app)
      else if (code == STACKALLOC) genStackalloc(app)
      else if (code == CQUOTE) genCQuoteOp(app)
      else if (code == CLASS_FIELD_RAWPTR) genClassFieldRawPtr(app)
      else {
        report.error(
          s"Unknown primitive operation: ${sym.fullName}(${fun.symbol.showName})",
          app.sourcePos
        )
        Val.Null
      }
    }

    private def genApplyTypeApply(app: Apply): Val = {
      val Apply(TypeApply(fun, targs), argsp) = app
      val Select(receiverp, _) = desugarTree(fun)
      given nir.Position = fun.span

      val funSym = fun.symbol
      val fromty = genType(receiverp.tpe)
      val toty = genType(targs.head.tpe)
      def boxty = genBoxType(targs.head.tpe)
      val value = genExpr(receiverp)
      def boxed = boxValue(receiverp.tpe, value)(using receiverp.span)

      if (funSym == defn.Any_isInstanceOf) buf.is(boxty, boxed, unwind)
      else if (funSym == defn.Any_asInstanceOf)
        (fromty, toty) match {
          case _ if boxed.ty == boxty =>
            boxed
          case (_: Type.PrimitiveKind, _: Type.PrimitiveKind) =>
            genCoercion(value, fromty, toty)
          case (_, Type.Nothing) =>
            val runtimeNothing = genType(defn.NothingClass)
            val isNullL, notNullL = fresh()
            val isNull = buf.comp(Comp.Ieq, boxed.ty, boxed, Val.Null, unwind)
            buf.branch(isNull, Next(isNullL), Next(notNullL))
            buf.label(isNullL)
            buf.raise(Val.Null, unwind)
            buf.label(notNullL)
            buf.as(runtimeNothing, boxed, unwind)
            buf.unreachable(unwind)
            buf.label(fresh())
            Val.Zero(Type.Nothing)
          case _ =>
            given nir.Position = app.span
            val cast = buf.as(boxty, boxed, unwind)
            unboxValue(app.tpe, partial = true, cast)
        }
      else if (funSym == defn.Object_synchronized)
        assert(argsp.size == 1, "synchronized with wrong number of args")
        genSynchronized(ValTree(boxed), argsp.head)
      else unsupported("Unkown case for genApplyTypeApply: " + funSym)
    }

    private def genApplyNew(app: Apply): Val = {
      val Apply(fun @ Select(New(tpt), nme.CONSTRUCTOR), args) = app
      given nir.Position = app.span

      fromType(tpt.tpe) match {
        case st if st.sym.isStruct =>
          genApplyNewStruct(st, args)

        case SimpleType(cls, Seq()) =>
          val ctor = fun.symbol
          assert(
            ctor.isClassConstructor,
            "'new' call to non-constructor: " + ctor.name
          )

          genApplyNew(cls, ctor, args)

        case SimpleType(sym, targs) =>
          unsupported(s"unexpected new: $sym with targs $targs")
      }
    }

    private def genApplyNewStruct(st: SimpleType, argsp: Seq[Tree]): Val = {
      val ty = genType(st)
      val args = genSimpleArgs(argsp)
      var res: Val = Val.Zero(ty)

      for
        ((arg, argp), idx) <- args.zip(argsp).zipWithIndex
        given nir.Position = argp.span
      do res = buf.insert(res, arg, Seq(idx), unwind)
      res
    }

    private def genApplyNew(clssym: Symbol, ctorsym: Symbol, args: List[Tree])(
        using nir.Position
    ): Val = {
      val alloc = buf.classalloc(genTypeName(clssym), unwind)
      val call = genApplyMethod(ctorsym, statically = true, alloc, args)
      alloc
    }

    def genApplyModuleMethod(
        module: Symbol,
        method: Symbol,
        args: Seq[Tree]
    )(using
        nir.Position
    ): Val = {
      val self = genModule(module)
      genApplyMethod(method, statically = true, self, args)
    }

    def genApplyMethod(
        sym: Symbol,
        statically: Boolean,
        selfp: Tree,
        argsp: Seq[Tree]
    )(using nir.Position): Val = {
      if (sym.isExtern && sym.is(Accessor)) genApplyExternAccessor(sym, argsp)
      else if (sym.isStaticInNIR && !sym.isExtern)
        genApplyStaticMethod(sym, selfp.symbol, argsp)
      else
        val self = genExpr(selfp)
        genApplyMethod(sym, statically, self, argsp)
    }

    private def genApplyMethod(
        sym: Symbol,
        statically: Boolean,
        self: Val,
        argsp: Seq[Tree]
    )(using nir.Position): Val = {
      assert(!sym.isStaticMethod, sym)
      val owner = sym.owner.asClass
      val name = genMethodName(sym)

      val origSig = genMethodSig(sym)
      val sig =
        if (sym.isExtern) genExternMethodSig(sym)
        else origSig
      val args = genMethodArgs(sym, argsp)

      val isStaticCall = statically || owner.isStruct || sym.isExtern
      val method =
        if (isStaticCall) Val.Global(name, nir.Type.Ptr)
        else
          val Global.Member(_, sig) = name
          buf.method(self, sig, unwind)
      val values =
        if (sym.isExtern) args
        else self +: args

      val res = buf.call(sig, method, values, unwind)

      if (!sym.isExtern) res
      else {
        val Type.Function(_, retty) = origSig
        fromExtern(retty, res)
      }
    }

    private def genApplyStaticMethod(
        sym: Symbol,
        receiver: Symbol,
        argsp: Seq[Tree]
    )(using nir.Position): Val = {
      require(!sym.isExtern, sym)

      val sig = genMethodSig(sym)
      val args = genMethodArgs(sym, argsp)
      val methodName = genStaticMemberName(sym, receiver)
      val method = Val.Global(methodName, nir.Type.Ptr)
      buf.call(sig, method, args, unwind)
    }

    private def genApplyExternAccessor(sym: Symbol, argsp: Seq[Tree])(using
        nir.Position
    ): Val = {
      argsp match {
        case Seq() =>
          val ty = genMethodSig(sym).ret
          val externTy = genExternMethodSig(sym).ret
          genLoadExtern(ty, externTy, sym)
        case Seq(valuep) =>
          val externTy = genExternType(valuep.tpe)
          genStoreExtern(externTy, sym, genExpr(valuep))
      }
    }

    // Utils
    private def boxValue(st: SimpleType, value: Val)(using
        nir.Position
    ): Val = {
      if (st.sym.isUnsignedType)
        genApplyModuleMethod(
          defnNir.RuntimeBoxesModule,
          defnNir.BoxUnsignedMethod(st.sym),
          Seq(ValTree(value))
        )
      else if (genPrimCode(st) == 'O') value
      else genApplyBox(st, ValTree(value))
    }

    private def unboxValue(st: SimpleType, partial: Boolean, value: Val)(using
        nir.Position
    ): Val = {
      if (st.sym.isUnsignedType) {
        // Results of asInstanceOfs are partially unboxed, meaning
        // that non-standard value types remain to be boxed.
        if (partial) value
        else
          genApplyModuleMethod(
            defnNir.RuntimeBoxesModule,
            defnNir.UnboxUnsignedMethod(st.sym),
            Seq(ValTree(value))
          )
      } else if (genPrimCode(st) == 'O') value
      else genApplyUnbox(st, ValTree(value))
    }

    private def genSimpleOp(app: Apply, args: List[Tree], code: Int): Val = {
      given nir.Position = app.span
      val retty = genType(app.tpe)

      args match {
        case List(right)       => genUnaryOp(code, right, retty)
        case List(left, right) => genBinaryOp(code, left, right, retty)
        case _ =>
          report.error(
            s"Too many arguments for primitive function: $app",
            app.sourcePos
          )
          Val.Null
      }
    }

    private def negateBool(value: nir.Val)(using nir.Position): Val =
      buf.bin(Bin.Xor, Type.Bool, Val.True, value, unwind)

    private def genUnaryOp(code: Int, rightp: Tree, opty: nir.Type): Val = {
      given nir.Position = rightp.span
      val right = genExpr(rightp)
      val coerced = genCoercion(right, right.ty, opty)
      val tpe = coerced.ty

      def numOfType(num: Int, ty: nir.Type): Val = ty match {
        case Type.Byte              => Val.Byte(num.toByte)
        case Type.Short | Type.Char => Val.Short(num.toShort)
        case Type.Int               => Val.Int(num)
        case Type.Long              => Val.Long(num.toLong)
        case Type.Float             => Val.Float(num.toFloat)
        case Type.Double            => Val.Double(num.toDouble)
        case _ => unsupported(s"num = $num, ty = ${ty.show}")
      }

      (opty, code) match {
        case (_: Type.I | _: Type.F, POS) => coerced
        case (_: Type.I, NOT) =>
          buf.bin(Bin.Xor, tpe, numOfType(-1, tpe), coerced, unwind)
        case (_: Type.F, NEG) =>
          buf.bin(Bin.Fsub, tpe, numOfType(0, tpe), coerced, unwind)
        case (_: Type.I, NEG) =>
          buf.bin(Bin.Isub, tpe, numOfType(0, tpe), coerced, unwind)
        case (Type.Bool, ZNOT) => negateBool(coerced)
        case _ =>
          report.error(s"Unknown unary operation code: $code", rightp.sourcePos)
          Val.Null
      }
    }

    private def genBinaryOp(
        code: Int,
        left: Tree,
        right: Tree,
        retty: nir.Type
    )(using nir.Position): Val = {
      val lty = genType(left.tpe)
      val rty = genType(right.tpe)
      val opty = {
        if (isShiftOp(code))
          if (lty == nir.Type.Long) nir.Type.Long
          else nir.Type.Int
        else
          binaryOperationType(lty, rty)
      }
      def genOp(op: (nir.Type, Val, Val) => Op): Val = {
        val leftcoerced = genCoercion(genExpr(left), lty, opty)(using left.span)
        val rightcoerced =
          genCoercion(genExpr(right), rty, opty)(using right.span)
        buf.let(op(opty, leftcoerced, rightcoerced), unwind)(using left.span)
      }

      val binres = opty match {
        case _: Type.F =>
          code match {
            case ADD => genOp(Op.Bin(Bin.Fadd, _, _, _))
            case SUB => genOp(Op.Bin(Bin.Fsub, _, _, _))
            case MUL => genOp(Op.Bin(Bin.Fmul, _, _, _))
            case DIV => genOp(Op.Bin(Bin.Fdiv, _, _, _))
            case MOD => genOp(Op.Bin(Bin.Frem, _, _, _))

            case EQ => genOp(Op.Comp(Comp.Feq, _, _, _))
            case NE => genOp(Op.Comp(Comp.Fne, _, _, _))
            case LT => genOp(Op.Comp(Comp.Flt, _, _, _))
            case LE => genOp(Op.Comp(Comp.Fle, _, _, _))
            case GT => genOp(Op.Comp(Comp.Fgt, _, _, _))
            case GE => genOp(Op.Comp(Comp.Fge, _, _, _))

            case _ =>
              report.error(
                s"Unknown floating point type binary operation code: $code",
                right.sourcePos
              )
              Val.Null
          }
        case Type.Bool | _: Type.I =>
          code match {
            case ADD => genOp(Op.Bin(Bin.Iadd, _, _, _))
            case SUB => genOp(Op.Bin(Bin.Isub, _, _, _))
            case MUL => genOp(Op.Bin(Bin.Imul, _, _, _))
            case DIV => genOp(Op.Bin(Bin.Sdiv, _, _, _))
            case MOD => genOp(Op.Bin(Bin.Srem, _, _, _))

            case OR  => genOp(Op.Bin(Bin.Or, _, _, _))
            case XOR => genOp(Op.Bin(Bin.Xor, _, _, _))
            case AND => genOp(Op.Bin(Bin.And, _, _, _))
            case LSL => genOp(Op.Bin(Bin.Shl, _, _, _))
            case LSR => genOp(Op.Bin(Bin.Lshr, _, _, _))
            case ASR => genOp(Op.Bin(Bin.Ashr, _, _, _))

            case EQ => genOp(Op.Comp(Comp.Ieq, _, _, _))
            case NE => genOp(Op.Comp(Comp.Ine, _, _, _))
            case LT => genOp(Op.Comp(Comp.Slt, _, _, _))
            case LE => genOp(Op.Comp(Comp.Sle, _, _, _))
            case GT => genOp(Op.Comp(Comp.Sgt, _, _, _))
            case GE => genOp(Op.Comp(Comp.Sge, _, _, _))

            case ZOR  => genIf(retty, left, Literal(Constant(true)), right)
            case ZAND => genIf(retty, left, right, Literal(Constant(false)))
            case _ =>
              report.error(
                s"Unknown integer type binary operation code: $code",
                right.sourcePos
              )
              Val.Null
          }
        case _: Type.RefKind =>
          def genEquals(ref: Boolean, negated: Boolean) = (left, right) match {
            // If null is present on either side, we must always
            // generate reference equality, regardless of where it
            // was called with == or eq. This shortcut is not optional.
            case (Literal(Constant(null)), _) | (_, Literal(Constant(null))) =>
              genClassEquality(left, right, ref = true, negated = negated)
            case _ =>
              genClassEquality(left, right, ref = ref, negated = negated)
          }

          code match {
            case EQ => genEquals(ref = false, negated = false)
            case NE => genEquals(ref = false, negated = true)
            case ID => genEquals(ref = true, negated = false)
            case NI => genEquals(ref = true, negated = true)
            case _ =>
              report.error(
                s"Unknown reference type binary operation code: $code",
                right.sourcePos
              )
              Val.Null
          }
        case Type.Ptr =>
          code match {
            case EQ | ID => genOp(Op.Comp(Comp.Ieq, _, _, _))
            case NE | NI => genOp(Op.Comp(Comp.Ine, _, _, _))
          }
        case ty =>
          report.error(
            s"Unknown binary operation type: $ty",
            right.sourcePos
          )
          Val.Null
      }

      genCoercion(binres, binres.ty, retty)(using right.span)
    }

    private def binaryOperationType(lty: nir.Type, rty: nir.Type) =
      (lty, rty) match {
        // Bug compatibility with scala/bug/issues/11253
        case (Type.Long, Type.Float)             => Type.Double
        case (nir.Type.Ptr, _: nir.Type.RefKind) => lty
        case (_: nir.Type.RefKind, nir.Type.Ptr) => rty
        case (nir.Type.Bool, nir.Type.Bool)      => nir.Type.Bool
        case (nir.Type.I(lwidth, _), nir.Type.I(rwidth, _))
            if lwidth < 32 && rwidth < 32 =>
          nir.Type.Int
        case (nir.Type.I(lwidth, _), nir.Type.I(rwidth, _)) =>
          if (lwidth >= rwidth) lty
          else rty
        case (nir.Type.I(_, _), nir.Type.F(_)) => rty
        case (nir.Type.F(_), nir.Type.I(_, _)) => lty
        case (nir.Type.F(lwidth), nir.Type.F(rwidth)) =>
          if (lwidth >= rwidth) lty
          else rty
        case (_: nir.Type.RefKind, _: nir.Type.RefKind) => Rt.Object
        case (ty1, ty2) if ty1 == ty2                   => ty1
        case (Type.Nothing, ty)                         => ty
        case (ty, Type.Nothing)                         => ty
        case _ =>
          report.error(s"can't perform binary operation between $lty and $rty")
          Type.Nothing
      }

    private def genClassEquality(
        leftp: Tree,
        rightp: Tree,
        ref: Boolean,
        negated: Boolean
    ): Val = {
      given nir.Position = leftp.span
      val left = genExpr(leftp)

      if (ref) {
        val right = genExpr(rightp)
        val comp = if (negated) Comp.Ine else Comp.Ieq
        buf.comp(comp, Rt.Object, left, right, unwind)
      } else {
        val thenn, elsen, mergen = fresh()
        val mergev = Val.Local(fresh(), nir.Type.Bool)

        val isnull = buf.comp(Comp.Ieq, Rt.Object, left, Val.Null, unwind)
        buf.branch(isnull, Next(thenn), Next(elsen))
        locally {
          buf.label(thenn)
          val right = genExpr(rightp)
          val thenv = buf.comp(Comp.Ieq, Rt.Object, right, Val.Null, unwind)
          buf.jump(mergen, Seq(thenv))
        }
        locally {
          buf.label(elsen)
          val elsev = genApplyMethod(
            defnNir.NObject_equals,
            statically = false,
            left,
            Seq(rightp)
          )
          buf.jump(mergen, Seq(elsev))
        }
        buf.label(mergen, Seq(mergev))
        if (negated) negateBool(mergev)
        else mergev
      }
    }

    def genMethodArgs(sym: Symbol, argsp: Seq[Tree]): Seq[Val] = {
      if (!sym.isExtern) genSimpleArgs(argsp)
      else {
        val res = Seq.newBuilder[Val]
        argsp.zip(sym.denot.paramSymss.flatten).foreach {
          case (argp, paramSym) =>
            given nir.Position = argp.span
            val externType = genExternType(paramSym.info.resultType)
            res += toExtern(externType, genExpr(argp))
        }
        res.result()
      }
    }

    private def genSimpleArgs(argsp: Seq[Tree]): Seq[Val] = {
      argsp.map(genExpr)
    }

    private def genArrayOp(app: Apply, code: Int): Val = {
      import NirPrimitives._
      import dotty.tools.backend.ScalaPrimitivesOps._

      val Apply(Select(arrayp, _), argsp) = app
      val Type.Array(elemty, _) = genType(arrayp.tpe)
      given nir.Position = app.span

      def elemcode = genArrayCode(arrayp.tpe)
      val array = genExpr(arrayp)

      if (code == ARRAY_CLONE)
        val method = defnNir.RuntimeArray_clone(elemcode)
        genApplyMethod(method, statically = true, array, argsp)
      else if (isArrayGet(code))
        val idx = genExpr(argsp(0))
        buf.arrayload(elemty, array, idx, unwind)
      else if (isArraySet(code))
        val idx = genExpr(argsp(0))
        val value = genExpr(argsp(1))
        buf.arraystore(elemty, array, idx, value, unwind)
      else buf.arraylength(array, unwind)
    }

    private def genHashCode(argp: Tree)(using nir.Position): Val = {
      val arg = boxValue(argp.tpe, genExpr(argp))
      val isnull =
        buf.comp(Comp.Ieq, Rt.Object, arg, Val.Null, unwind)(using
          argp.span: nir.Position
        )
      val cond = ValTree(isnull)
      val thenp = ValTree(Val.Int(0))
      val elsep = ContTree { () =>
        val meth = defnNir.NObject_hashCode
        genApplyMethod(meth, statically = false, arg, Seq())
      }
      genIf(Type.Int, cond, thenp, elsep)
    }

    private def genStringConcat(leftp: Tree, rightp: Tree): Val = {
      def stringify(sym: Symbol, value: Val)(using nir.Position): Val = {
        val cond = ContTree { () =>
          buf.comp(Comp.Ieq, Rt.Object, value, Val.Null, unwind)
        }
        val thenp = ContTree { () => Val.String("null") }
        val elsep = ContTree { () =>
          if (sym == defn.StringClass) value
          else {
            val meth = defn.Any_toString
            genApplyMethod(meth, statically = false, value, Seq())
          }
        }
        genIf(Rt.String, cond, thenp, elsep)
      }

      val left = {
        given nir.Position = leftp.span
        val typesym = leftp.tpe.typeSymbol
        val unboxed = genExpr(leftp)
        val boxed = boxValue(typesym, unboxed)
        stringify(typesym, boxed)
      }

      val right = {
        given nir.Position = rightp.span
        val typesym = rightp.tpe.typeSymbol
        val boxed = genExpr(rightp)
        stringify(typesym, boxed)
      }

      genApplyMethod(
        defn.String_+,
        statically = true,
        left,
        Seq(ValTree(right))
      )(using leftp.span: nir.Position)
    }

    private def genStaticMember(sym: Symbol, receiver: Symbol)(using
        nir.Position
    ): Val = {
      /* Actually, there is no static member in Scala Native. If we come here, that
       * is because we found the symbol in a Java-emitted .class in the
       * classpath. But the corresponding implementation in Scala Native will
       * actually be a val in the companion module.
       */

      if (sym == defn.BoxedUnit_UNIT) Val.Unit
      else if (sym == defn.BoxedUnit_TYPE) Val.Unit
      else genApplyStaticMethod(sym, receiver, Seq())
    }

    private def genSynchronized(receiverp: Tree, bodyp: Tree)(using
        nir.Position
    ): Val = {
      genSynchronized(receiverp)(_.genExpr(bodyp))
    }

    def genSynchronized(
        receiverp: Tree
    )(bodyGen: ExprBuffer => Val)(using nir.Position): Val = {
      val monitor =
        genApplyModuleMethod(
          defnNir.RuntimePackageClass,
          defnNir.RuntimePackage_getMonitor,
          Seq(receiverp)
        )
      val enter = genApplyMethod(
        defnNir.RuntimeMonitor_enter,
        statically = true,
        monitor,
        Seq()
      )
      val ret = bodyGen(this)
      val exit = genApplyMethod(
        defnNir.RuntimeMonitor_exit,
        statically = true,
        monitor,
        Seq()
      )

      ret
    }

    private def genThrow(tree: Tree, args: List[Tree]): Val = {
      given nir.Position = tree.span
      val exception = args.head
      val res = genExpr(exception)
      buf.raise(res, unwind)
      Val.Unit
    }

    def genCastOp(from: nir.Type, to: nir.Type, value: Val)(using
        nir.Position
    ): Val =
      castConv(from, to).fold(value)(buf.conv(_, to, value, unwind))

    private def genCoercion(app: Apply, receiver: Tree, code: Int): Val = {
      given nir.Position = app.span
      val rec = genExpr(receiver)
      val (fromty, toty) = coercionTypes(code)

      genCoercion(rec, fromty, toty)
    }

    private def genCoercion(value: Val, fromty: nir.Type, toty: nir.Type)(using
        nir.Position
    ): Val = {
      if (fromty == toty) value
      else if (fromty == nir.Type.Nothing || toty == nir.Type.Nothing) value
      else {
        val conv = (fromty, toty) match {
          case (nir.Type.Ptr, _: nir.Type.RefKind) => Conv.Bitcast
          case (_: nir.Type.RefKind, nir.Type.Ptr) => Conv.Bitcast
          case (nir.Type.I(fromw, froms), nir.Type.I(tow, tos)) =>
            if (fromw < tow)
              if (froms) Conv.Sext
              else Conv.Zext
            else if (fromw > tow) Conv.Trunc
            else Conv.Bitcast
          case (nir.Type.I(_, true), _: nir.Type.F)  => Conv.Sitofp
          case (nir.Type.I(_, false), _: nir.Type.F) => Conv.Uitofp
          case (_: nir.Type.F, nir.Type.I(iwidth, true)) =>
            if (iwidth < 32) {
              val ivalue = genCoercion(value, fromty, Type.Int)
              return genCoercion(ivalue, Type.Int, toty)
            }
            Conv.Fptosi
          case (_: nir.Type.F, nir.Type.I(iwidth, false)) =>
            if (iwidth < 32) {
              val ivalue = genCoercion(value, fromty, Type.Int)
              return genCoercion(ivalue, Type.Int, toty)
            }
            Conv.Fptoui
          case (nir.Type.Double, nir.Type.Float) => Conv.Fptrunc
          case (nir.Type.Float, nir.Type.Double) => Conv.Fpext
          case _ =>
            report.error(
              s"Unsupported coercion types: from $fromty to $toty"
            )
            Conv.Bitcast
        }
        buf.conv(conv, toty, value, unwind)
      }
    }

    private def coercionTypes(code: Int): (nir.Type, nir.Type) = {
      code match {
        case B2B => (nir.Type.Byte, nir.Type.Byte)
        case B2S => (nir.Type.Byte, nir.Type.Short)
        case B2C => (nir.Type.Byte, nir.Type.Char)
        case B2I => (nir.Type.Byte, nir.Type.Int)
        case B2L => (nir.Type.Byte, nir.Type.Long)
        case B2F => (nir.Type.Byte, nir.Type.Float)
        case B2D => (nir.Type.Byte, nir.Type.Double)

        case S2B => (nir.Type.Short, nir.Type.Byte)
        case S2S => (nir.Type.Short, nir.Type.Short)
        case S2C => (nir.Type.Short, nir.Type.Char)
        case S2I => (nir.Type.Short, nir.Type.Int)
        case S2L => (nir.Type.Short, nir.Type.Long)
        case S2F => (nir.Type.Short, nir.Type.Float)
        case S2D => (nir.Type.Short, nir.Type.Double)

        case C2B => (nir.Type.Char, nir.Type.Byte)
        case C2S => (nir.Type.Char, nir.Type.Short)
        case C2C => (nir.Type.Char, nir.Type.Char)
        case C2I => (nir.Type.Char, nir.Type.Int)
        case C2L => (nir.Type.Char, nir.Type.Long)
        case C2F => (nir.Type.Char, nir.Type.Float)
        case C2D => (nir.Type.Char, nir.Type.Double)

        case I2B => (nir.Type.Int, nir.Type.Byte)
        case I2S => (nir.Type.Int, nir.Type.Short)
        case I2C => (nir.Type.Int, nir.Type.Char)
        case I2I => (nir.Type.Int, nir.Type.Int)
        case I2L => (nir.Type.Int, nir.Type.Long)
        case I2F => (nir.Type.Int, nir.Type.Float)
        case I2D => (nir.Type.Int, nir.Type.Double)

        case L2B => (nir.Type.Long, nir.Type.Byte)
        case L2S => (nir.Type.Long, nir.Type.Short)
        case L2C => (nir.Type.Long, nir.Type.Char)
        case L2I => (nir.Type.Long, nir.Type.Int)
        case L2L => (nir.Type.Long, nir.Type.Long)
        case L2F => (nir.Type.Long, nir.Type.Float)
        case L2D => (nir.Type.Long, nir.Type.Double)

        case F2B => (nir.Type.Float, nir.Type.Byte)
        case F2S => (nir.Type.Float, nir.Type.Short)
        case F2C => (nir.Type.Float, nir.Type.Char)
        case F2I => (nir.Type.Float, nir.Type.Int)
        case F2L => (nir.Type.Float, nir.Type.Long)
        case F2F => (nir.Type.Float, nir.Type.Float)
        case F2D => (nir.Type.Float, nir.Type.Double)

        case D2B => (nir.Type.Double, nir.Type.Byte)
        case D2S => (nir.Type.Double, nir.Type.Short)
        case D2C => (nir.Type.Double, nir.Type.Char)
        case D2I => (nir.Type.Double, nir.Type.Int)
        case D2L => (nir.Type.Double, nir.Type.Long)
        case D2F => (nir.Type.Double, nir.Type.Float)
        case D2D => (nir.Type.Double, nir.Type.Double)
      }
    }

    private def castConv(fromty: nir.Type, toty: nir.Type): Option[nir.Conv] =
      (fromty, toty) match {
        case (_: Type.I, Type.Ptr)                   => Some(nir.Conv.Inttoptr)
        case (Type.Ptr, _: Type.I)                   => Some(nir.Conv.Ptrtoint)
        case (_: Type.RefKind, Type.Ptr)             => Some(nir.Conv.Bitcast)
        case (Type.Ptr, _: Type.RefKind)             => Some(nir.Conv.Bitcast)
        case (_: Type.RefKind, _: Type.RefKind)      => Some(nir.Conv.Bitcast)
        case (_: Type.RefKind, _: Type.I)            => Some(nir.Conv.Ptrtoint)
        case (_: Type.I, _: Type.RefKind)            => Some(nir.Conv.Inttoptr)
        case (Type.I(w1, _), Type.F(w2)) if w1 == w2 => Some(nir.Conv.Bitcast)
        case (Type.F(w1), Type.I(w2, _)) if w1 == w2 => Some(nir.Conv.Bitcast)
        case _ if fromty == toty                     => None
        case _ => unsupported(s"cast from $fromty to $toty")
      }

    /** Boxes a value of the given type before `elimErasedValueType`.
     *
     *  This should be used when sending values to a LLVM context, which is
     *  erased/boxed at the NIR level, although it is not erased at the
     *  dotty/JVM level.
     *
     *  @param value
     *    Value to be boxed if needed.
     *  @param tpeEnteringElimErasedValueType
     *    The type of `value` as it was entering the `elimErasedValueType`
     *    phase.
     */
    private def ensureBoxed(
        value: Val,
        tpeEnteringPosterasure: core.Types.Type
    )(using buf: ExprBuffer, pos: nir.Position): Val = {
      tpeEnteringPosterasure match {
        case tpe if tpe.isPrimitiveValueType =>
          buf.boxValue(tpe, value)

        case ErasedValueType(valueClass, _) =>
          val boxedClass = valueClass.typeSymbol.asClass
          val ctorName = genMethodName(boxedClass.primaryConstructor)
          val ctorSig = genMethodSig(boxedClass.primaryConstructor)

          val alloc = buf.classalloc(genTypeName(boxedClass), unwind)
          val ctor = buf.method(
            alloc,
            ctorName.asInstanceOf[nir.Global.Member].sig,
            unwind
          )
          buf.call(ctorSig, ctor, Seq(alloc, value), unwind)

          alloc

        case _ =>
          value
      }
    }

    /** Unboxes a value typed as Any to the given type before
     *  `elimErasedValueType`.
     *
     *  This should be used when receiving values from a LLVM context, which is
     *  erased/boxed at the NIR level, although it is not erased at the
     *  dotty/JVM level.
     *
     *  @param value
     *    Tree to be extracted.
     *  @param tpeEnteringElimErasedValueType
     *    The type of `value` as it was entering the `elimErasedValueType`
     *    phase.
     */
    private def ensureUnboxed(
        value: Val,
        tpeEnteringPosterasure: core.Types.Type
    )(using
        buf: ExprBuffer,
        pos: nir.Position
    ): Val = {
      tpeEnteringPosterasure match {
        case tpe if tpe.isPrimitiveValueType =>
          val targetTpe = genType(tpeEnteringPosterasure)
          if (targetTpe == value.ty) value
          else buf.unbox(genBoxType(tpe), value, Next.None)

        case ErasedValueType(valueClass, _) =>
          val boxedClass = valueClass.typeSymbol.asClass
          val unboxMethod = ValueClasses.valueClassUnbox(boxedClass)
          val castedValue = buf.genCastOp(value.ty, genType(valueClass), value)
          buf.genApplyMethod(
            sym = unboxMethod,
            statically = false,
            self = castedValue,
            argsp = Nil
          )

        case tpe =>
          val unboxed = buf.unboxValue(tpe, partial = true, value)
          if (unboxed == value) // no need to or cannot unbox, we should cast
            buf.genCastOp(genType(tpeEnteringPosterasure), genType(tpe), value)
          else unboxed
      }
    }

    // Native specifc features
    private def genRawPtrOp(app: Apply, code: Int): Val = {
      if (NirPrimitives.isRawPtrLoadOp(code)) genRawPtrLoadOp(app, code)
      else if (NirPrimitives.isRawPtrStoreOp(code)) genRawPtrStoreOp(app, code)
      else if (code == NirPrimitives.ELEM_RAW_PTR) genRawPtrElemOp(app, code)
      else {
        report.error(s"Unknown pointer operation #$code : $app", app.sourcePos)
        Val.Null
      }
    }

    private def genRawPtrLoadOp(app: Apply, code: Int): Val = {
      import NirPrimitives._
      given nir.Position = app.span

      val Apply(_, Seq(ptrp)) = app
      val ptr = genExpr(ptrp)
      val ty = code match {
        case LOAD_BOOL    => nir.Type.Bool
        case LOAD_CHAR    => nir.Type.Char
        case LOAD_BYTE    => nir.Type.Byte
        case LOAD_SHORT   => nir.Type.Short
        case LOAD_INT     => nir.Type.Int
        case LOAD_LONG    => nir.Type.Long
        case LOAD_FLOAT   => nir.Type.Float
        case LOAD_DOUBLE  => nir.Type.Double
        case LOAD_RAW_PTR => nir.Type.Ptr
        case LOAD_OBJECT  => Rt.Object
      }
      buf.load(ty, ptr, unwind)
    }

    private def genRawPtrStoreOp(app: Apply, code: Int): Val = {
      import NirPrimitives._
      given nir.Position = app.span
      val Apply(_, Seq(ptrp, valuep)) = app

      val ptr = genExpr(ptrp)
      val value = genExpr(valuep)
      val ty = code match {
        case STORE_BOOL    => nir.Type.Bool
        case STORE_CHAR    => nir.Type.Char
        case STORE_BYTE    => nir.Type.Byte
        case STORE_SHORT   => nir.Type.Short
        case STORE_INT     => nir.Type.Int
        case STORE_LONG    => nir.Type.Long
        case STORE_FLOAT   => nir.Type.Float
        case STORE_DOUBLE  => nir.Type.Double
        case STORE_RAW_PTR => nir.Type.Ptr
        case STORE_OBJECT  => Rt.Object
      }
      buf.store(ty, ptr, value, unwind)
    }

    private def genRawPtrElemOp(app: Apply, code: Int): Val = {
      given nir.Position = app.span
      val Apply(_, Seq(ptrp, offsetp)) = app

      val ptr = genExpr(ptrp)
      val offset = genExpr(offsetp)
      buf.elem(Type.Byte, ptr, Seq(offset), unwind)
    }

    private def genRawCastOp(app: Apply, code: Int): Val = {
      given nir.Position = app.span
      val Apply(_, Seq(argp)) = app

      val fromty = genType(argp.tpe)
      val toty = genType(app.tpe)
      val value = genExpr(argp)

      genCastOp(fromty, toty, value)
    }

    private def genUnsignedOp(app: Tree, code: Int): Val = {
      given nir.Position = app.span
      import NirPrimitives._
      def castUnsignedInteger = code >= BYTE_TO_UINT && code <= INT_TO_ULONG
      def castUnsignedToFloat = code >= UINT_TO_FLOAT && code <= ULONG_TO_DOUBLE
      app match {
        case Apply(_, Seq(argp)) if castUnsignedInteger =>
          val ty = genType(app.tpe)
          val arg = genExpr(argp)

          buf.conv(Conv.Zext, ty, arg, unwind)

        case Apply(_, Seq(argp)) if castUnsignedToFloat =>
          val ty = genType(app.tpe)
          val arg = genExpr(argp)

          buf.conv(Conv.Uitofp, ty, arg, unwind)

        case Apply(_, Seq(leftp, rightp)) =>
          val bin = code match {
            case DIV_UINT | DIV_ULONG => nir.Bin.Udiv
            case REM_UINT | REM_ULONG => nir.Bin.Urem
          }
          val ty = genType(leftp.tpe)
          val left = genExpr(leftp)
          val right = genExpr(rightp)

          buf.bin(bin, ty, left, right, unwind)
      }
    }

    private def getLinktimeCondition(condp: Tree): Option[LinktimeCondition] = {
      import nir.LinktimeCondition._
      def genComparsion(name: Name, value: Val): Comp = {
        def intOrFloatComparison(onInt: Comp, onFloat: Comp) = value.ty match {
          case _: Type.F => onFloat
          case _         => onInt
        }

        import Comp._
        name match {
          case nme.EQ => intOrFloatComparison(Ieq, Feq)
          case nme.NE => intOrFloatComparison(Ine, Fne)
          case nme.GT => intOrFloatComparison(Sgt, Fgt)
          case nme.GE => intOrFloatComparison(Sge, Fge)
          case nme.LT => intOrFloatComparison(Slt, Flt)
          case nme.LE => intOrFloatComparison(Sle, Fle)
          case nme =>
            report.error(s"Unsupported condition '$nme'", condp.sourcePos)
            Comp.Ine
        }
      }

      condp match {
        // if(bool) (...)
        case Apply(LinktimeProperty(name, position), List()) =>
          Some {
            SimpleCondition(
              propertyName = name,
              comparison = Comp.Ieq,
              value = Val.True
            )(using position)
          }

        // if(!bool) (...)
        case Apply(
              Select(
                Apply(LinktimeProperty(name, position), List()),
                nme.UNARY_!
              ),
              List()
            ) =>
          Some {
            SimpleCondition(
              propertyName = name,
              comparison = Comp.Ieq,
              value = Val.False
            )(using position)
          }

        // if(property <comp> x) (...)
        case Apply(
              Select(LinktimeProperty(name, position), comp),
              List(arg @ Literal(Constant(_)))
            ) =>
          Some {
            val argValue = genLiteralValue(arg)
            SimpleCondition(
              propertyName = name,
              comparison = genComparsion(comp, argValue),
              value = argValue
            )(using position)
          }

        // special case for Scala 3 - it wraps != into !(_ == _)
        // if(property == x) (...)
        case Apply(
              Select(
                Apply(
                  Select(LinktimeProperty(name, position), nme.EQ),
                  List(arg @ Literal(Constant(_)))
                ),
                nme.UNARY_!
              ),
              List()
            ) =>
          Some {
            val argValue = genLiteralValue(arg)
            SimpleCondition(
              propertyName = name,
              comparison = genComparsion(nme.NE, argValue),
              value = argValue
            )(using position)
          }

        // if(cond1 {&&,||} cond2) (...)
        case Apply(Select(cond1, op), List(cond2)) =>
          (getLinktimeCondition(cond1), getLinktimeCondition(cond2)) match {
            case (Some(c1), Some(c2)) =>
              val bin = op match {
                case nme.ZAND => Bin.And
                case nme.ZOR  => Bin.Or
              }
              given nir.Position = condp.span
              Some(ComplexCondition(bin, c1, c2))
            case (None, None) => None
            case _ =>
              report.error(
                "Mixing link-time and runtime conditions is not allowed",
                condp.sourcePos
              )
              None
          }

        case _ => None
      }
    }

    private def genStackalloc(app: Apply): Val = {
      val Apply(_, Seq(sizep)) = app

      val size = genExpr(sizep)
      val unboxed = buf.unbox(size.ty, size, unwind)(using sizep.span)

      buf.stackalloc(nir.Type.Byte, unboxed, unwind)(using app.span)
    }

    def genCQuoteOp(app: Apply): Val = {
      app match {
        // case q"""
        //   scala.scalanative.unsafe.`package`.CQuote(
        //     new StringContext(scala.this.Predef.wrapRefArray(
        //       Array[String]{${str: String}}.$asInstanceOf[Array[Object]]()
        //     ))
        //   ).c()
        case Apply(
              Select(
                Apply(
                  _, //Ident(CQuote),
                  List(
                    Apply(
                      _,
                      List(Apply(_, List(javaSeqLiteral: JavaSeqLiteral)))
                    )
                  )
                ),
                _
              ),
              _
            ) =>
          given nir.Position = app.span
          val List(Literal(Constant(str: String))) = javaSeqLiteral.elems
          val chars = Val.Chars(StringUtils.processEscapes(str).toIndexedSeq)
          val const = Val.Const(chars)
          buf.box(nir.Rt.BoxedPtr, const, unwind)

        case _ =>
          report.error("Failed to interpret CQuote", app.sourcePos)
          Val.Null
      }
    }

    def genClassFieldRawPtr(app: Apply): Val = {
      given nir.Position = app.span
      val Apply(_, List(target, fieldName: Literal)) = app
      val fieldNameId = fieldName.const.stringValue
      val classInfo = target.tpe.finalResultType
      val classInfoSym = classInfo.typeSymbol.asClass
      def matchesName(f: SingleDenotation) =
        f.name.mangled == termName(fieldNameId).mangled
      def isImmutableField(f: SymDenotation) = {
        // If `val` was defined in trait it would be internally mutable, but with stable accessors
        !f.is(Mutable) || classInfoSym.parentSyms.exists(s =>
          s.asClass.info.decls.exists { f =>
            matchesName(f) && f.asSymDenotation
              .isAllOf(Method | Accessor, butNot = Mutable)
          }
        )
      }

      val allFields =
        classInfoSym.info.fields ++ classInfoSym.info.parents.flatMap(_.fields)
      allFields
        .collectFirst {
          case f if matchesName(f) =>
            // Don't allow to get pointer to immutable field, as it might allow for mutation
            if (isImmutableField(f.asSymDenotation)) {
              val owner = f.asSymDenotation.owner
              report.error(
                s"Resolving pointer of immutable field ${fieldNameId} in ${owner.show} is not allowed"
              )
            }
            buf.field(genExpr(target), genFieldName(f.symbol), unwind)
        }
        .getOrElse {
          report.error(
            s"${classInfoSym.show} does not contain field ${fieldNameId}",
            app.sourcePos
          )
          Val.Int(-1)
        }
    }

    def genLoadExtern(ty: nir.Type, externTy: nir.Type, sym: Symbol)(using
        nir.Position
    ): Val = {
      assert(sym.isExtern, "loadExtern was not extern")

      val name = Val.Global(genName(sym), Type.Ptr)

      fromExtern(ty, buf.load(externTy, name, unwind))
    }

    def genStoreExtern(externTy: nir.Type, sym: Symbol, value: Val)(using
        nir.Position
    ): Val = {
      assert(sym.isExtern, "storeExtern was not extern")
      val name = Val.Global(genName(sym), Type.Ptr)
      val externValue = toExtern(externTy, value)

      buf.store(externTy, name, externValue, unwind)
    }

    def toExtern(expectedTy: nir.Type, value: Val)(using nir.Position): Val =
      (expectedTy, value.ty) match {
        case (_, refty: Type.Ref)
            if Type.boxClasses.contains(refty.name)
              && Type.unbox(Type.Ref(refty.name)) == expectedTy =>
          buf.unbox(Type.Ref(refty.name), value, unwind)
        case _ =>
          value
      }

    def fromExtern(expectedTy: nir.Type, value: Val)(using nir.Position): Val =
      (expectedTy, value.ty) match {
        case (refty: nir.Type.Ref, ty)
            if Type.boxClasses.contains(refty.name)
              && Type.unbox(Type.Ref(refty.name)) == ty =>
          buf.box(Type.Ref(refty.name), value, unwind)
        case _ =>
          value
      }

    /** Generates direct call to function ptr with optional unboxing arguments
     *  and boxing result Apply.args can contain different number of arguments
     *  depending on usage, however they are passed in constant order:
     *    - 0..N args
     *    - 0..N+1 type evidences of args (scalanative.Tag)
     *    - return type evidence
     */
    private def genCFuncPtrApply(app: Apply): Val = {
      given nir.Position = app.span
      val Apply(appRec @ Select(receiverp, _), aargs) = app

      val argsp = if (aargs.size > 2) aargs.take(aargs.length / 2) else Nil
      val evidences = aargs.drop(aargs.length / 2)

      val self = genExpr(receiverp)

      val retTypeEv = evidences.last
      val unwrappedRetType = unwrapTag(retTypeEv)
      val retType = genType(unwrappedRetType)
      val unboxedRetType = Type.unbox.getOrElse(retType, retType)

      val args = argsp
        .zip(evidences)
        .map {
          case (Apply(Select(_, nme.box), List(value)), _) =>
            genExpr(value)
          case (arg, evidence) =>
            given nir.Position = arg.span
            val tag = unwrapTag(evidence)
            val tpe = genType(tag)
            val obj = genExpr(arg)

            /* buf.unboxValue does not handle Ref( Ptr | CArray | ... ) unboxing
             * That's why we're doing it directly */
            if (Type.unbox.isDefinedAt(tpe)) buf.unbox(tpe, obj, unwind)
            else buf.unboxValue(tag, partial = false, obj)
        }
      val argTypes = args.map(_.ty)
      val funcSig = Type.Function(argTypes, unboxedRetType)

      val selfName = genTypeName(defnNir.CFuncPtrClass)
      val getRawPtrName = selfName
        .member(Sig.Field("rawptr", Sig.Scope.Private(selfName)))

      val target = buf.fieldload(Type.Ptr, self, getRawPtrName, unwind)
      val result = buf.call(funcSig, target, args, unwind)
      if (retType != unboxedRetType) buf.box(retType, result, unwind)
      else boxValue(unwrappedRetType, result)
    }

    private final val ExternForwarderSig = Sig.Generated("$extern$forwarder")

    private def genCFuncFromScalaFunction(app: Apply): Val = {
      given pos: nir.Position = app.span
      val fn :: evidences = app.args
      val paramTypes = evidences.map(unwrapTag)

      @tailrec
      def resolveFunction(tree: Tree): Val = tree match {
        case Typed(expr, _) => resolveFunction(expr)
        case Block(_, expr) => resolveFunction(expr)
        case fn @ Closure(_, target, _) =>
          val fnRef = genClosure(fn)
          val Type.Ref(className, _, _) = fnRef.ty

          generatedDefns += genFuncExternForwarder(
            className,
            target.symbol,
            paramTypes
          )
          fnRef

        case ref: RefTree =>
          report.error(
            s"Function passed to ${app.symbol.show} needs to be inlined",
            tree.sourcePos
          )
          Val.Null

        case _ =>
          report.error(
            "Failed to resolve function ref for extern forwarder",
            tree.sourcePos
          )
          Val.Null
      }

      val fnRef = resolveFunction(fn)
      val className = genTypeName(app.tpe.sym)

      val ctorTy = nir.Type.Function(
        Seq(Type.Ref(className), Type.Ptr),
        Type.Unit
      )
      val ctorName = className.member(Sig.Ctor(Seq(Type.Ptr)))
      val rawptr = buf.method(fnRef, ExternForwarderSig, unwind)

      val alloc = buf.classalloc(className, unwind)
      buf.call(
        ctorTy,
        Val.Global(ctorName, Type.Ptr),
        Seq(alloc, rawptr),
        unwind
      )
      alloc
    }

    private def genFuncExternForwarder(
        funcName: Global,
        funSym: Symbol,
        evidences: List[SimpleType]
    )(using nir.Position): Defn = {
      val attrs = Attrs(isExtern = true)

      // In case if passed function is adapted closure it's param types
      // would be erased, in such case we would recover original types
      // using evidence types (materialized unsafe.Tags)
      val isAdapted = funSym.name.mangledString.contains("$adapted$")
      val sig = genMethodSig(funSym)
      val externSig = genExternMethodSig(funSym)

      val Type.Function(origtys, _) =
        if (!isAdapted) sig
        else {
          val params :+ retty = evidences
            .map(genType)
            .map(t => nir.Type.box.getOrElse(t, t))
          Type.Function(params, retty)
        }

      val forwarderSig @ Type.Function(paramtys, retty) =
        if (!isAdapted) externSig
        else {
          val params :+ retty = evidences
            .map(genExternType)
            .map(t => nir.Type.unbox.getOrElse(t, t))
          Type.Function(params, retty)
        }

      val methodName = genMethodName(funSym)
      val method = Val.Global(methodName, Type.Ptr)

      val forwarderName = funcName.member(ExternForwarderSig)
      val forwarderBody = scoped(
        curUnwindHandler := None
      ) {
        val fresh = Fresh()
        val buf = ExprBuffer(using fresh)

        val params = paramtys.map(ty => Val.Local(fresh(), ty))
        buf.label(fresh(), params)

        val origTypes = if (funSym.isStaticInNIR) origtys else origtys.tail
        val boxedParams = origTypes.zip(params).map(buf.fromExtern(_, _))
        val argsp = boxedParams.map(ValTree(_))
        val res =
          if (funSym.isStaticInNIR)
            buf.genApplyStaticMethod(funSym, NoSymbol, argsp)
          else
            val owner = buf.genModule(funSym.owner)
            val selfp = ValTree(owner)
            buf.genApplyMethod(funSym, statically = true, selfp, argsp)

        val unboxedRes = buf.toExtern(retty, res)
        buf.ret(unboxedRes)

        buf.toSeq
      }
      Defn.Define(attrs, forwarderName, forwarderSig, forwarderBody)
    }

    private object LinktimeProperty {
      def unapply(tree: Tree): Option[(String, nir.Position)] = {
        if (tree.symbol == null) None
        else {
          tree.symbol
            .getAnnotation(defnNir.ResolvedAtLinktimeClass)
            .flatMap(_.argumentConstantString(0).orElse {
              report.error(
                "Name used to resolve link-time property needs to be non-null literal constant",
                tree.sourcePos
              )
              None
            })
            .zip(Some(fromSpan(tree.span)))
        }
      }
    }

  }

  sealed class FixupBuffer(using fresh: Fresh) extends nir.Buffer {
    private var labeled = false

    override def +=(inst: Inst): Unit = {
      given nir.Position = inst.pos
      inst match {
        case inst: nir.Inst.Label =>
          if (labeled) {
            unreachable(unwind)
          }
          labeled = true
        case _ =>
          if (!labeled) {
            label(fresh())
          }
          labeled = !inst.isInstanceOf[nir.Inst.Cf]
      }
      super.+=(inst)
      inst match {
        case Inst.Let(_, op, _) if op.resty == Type.Nothing =>
          unreachable(unwind)
          label(fresh())
        case _ =>
          ()
      }
    }

    override def ++=(insts: Seq[Inst]): Unit =
      insts.foreach { inst => this += inst }

    override def ++=(other: nir.Buffer): Unit =
      this ++= other.toSeq
  }
}
