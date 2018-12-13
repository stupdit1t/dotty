import scala.tasty.Reflection

trait Ref {
  def get: Any
}

class Eager(val get: Any) extends Ref

class Lazy(thunk: => Any) extends Ref {
  lazy val get: Any = thunk
}

class Var(private var value: Any) extends Ref {
  def get = value

  def update(rhs: Any): Unit = {
    value = rhs
  }
}

class Interpreter[R <: Reflection & Singleton](val reflect: R)(implicit ctx: reflect.Context) {
  import reflect._

  val jvmReflection = new JVMReflection(reflect)

  type Env = Map[Symbol, Ref]

  object Call {
    def unapply(arg: Tree): Option[(Tree, List[List[Term]])] = arg match {
      case fn@ Term.Select(_, _) => Some((fn, Nil))
      case fn@ Term.Ident(_) => Some((fn, Nil))
      case Term.Apply(Call(fn, args1), args2) => Some((fn, args1 :+ args2))
      case Term.TypeApply(Call(fn, args), _) => Some((fn, args))
      case _ => None
    }
  }

  def interpretCall(fn: Tree, argss: List[List[Term]])(implicit env: Env): Any = {
    (fn, argss) match {
      case (Term.Ident("println"), List(List(arg))) => println(eval(arg))
      case (Term.Ident("println"), List(List())) => println()
      case (_, Nil) | (_, List(List())) => fn.symbol match {
        case IsDefSymbol(sym) => eval(sym.tree.rhs.get)
        case _ => env(fn.symbol).get
      }
      case (_, x :: Nil) =>
        fn.symbol match {
          // TODO: obviously
          case IsDefSymbol(sym) =>
            val evaluatedArgs = x.map(arg => new Eager(eval(arg)))
            val env1 = env ++ sym.tree.paramss.head.map(_.symbol).zip(evaluatedArgs)
            eval(sym.tree.rhs.get)(env1)

        }
    }
  }

  def reflectCall(fn: Tree, argss: List[List[Term]])(implicit env: Env): Any = {
    (fn, argss) match {
      case (_, Nil) | (_, List(List())) => fn.symbol match {
        case IsDefSymbol(sym) => jvmReflection.interpretStaticMethodCall(fn.symbol.owner, fn.symbol, Nil)
        case _ =>
          if (fn.symbol.flags.isObject) {
            jvmReflection.loadModule(fn.symbol.asVal.moduleClass.get)
          }
          // call to a static val
          else {
            jvmReflection.interpretStaticVal(fn.symbol.owner, fn.symbol)
          }
      }
      case (_, x :: Nil) =>
        import Term._
        fn.symbol match {
          // TODO: obviously
          case IsDefSymbol(sym) =>
            if(sym.name == "==") eval(Term.IsSelect.unapply(fn).get.qualifier).asInstanceOf[Int] == eval(x.head).asInstanceOf[Int]
            else if(sym.name == ">") eval(Term.IsSelect.unapply(fn).get.qualifier).asInstanceOf[Int] > eval(x.head).asInstanceOf[Int]
            else if(sym.name == "-") eval(Term.IsSelect.unapply(fn).get.qualifier).asInstanceOf[Int] - eval(x.head).asInstanceOf[Int]
            else if(sym.name == "+") eval(Term.IsSelect.unapply(fn).get.qualifier).asInstanceOf[Int] + eval(x.head).asInstanceOf[Int]
            else {
              def args: List[Object] = argss.flatMap((a: List[Term]) => a.map(b => eval(b).asInstanceOf[Object]))
              jvmReflection.interpretStaticMethodCall(fn.symbol.owner, fn.symbol, args)
            }
        }
    }
  }

  def eval(tree: Statement)(implicit env: Env): Any = {
    tree match {
      case Call(fn, argss) =>
        fn match {
          case Term.Ident("println") => interpretCall(fn, argss)
          case _ => if (fn.symbol.isDefinedInCurrentRun) interpretCall(fn, argss) else reflectCall(fn, argss)
        }

      case Term.Assign(lhs, rhs) =>
        env(lhs.symbol) match {
          case varf: Var => varf.update(eval(rhs))
        }

      case Term.If(cond, thenp, elsep) =>
        if(eval(cond).asInstanceOf[Boolean])
          eval(thenp)
        else
          eval(elsep)

      case Term.While(cond, expr) =>
        while(eval(cond).asInstanceOf[Boolean]){
          eval(expr)
        }

      case Term.Block(stats, expr) =>
        val newEnv = stats.foldLeft(env)((accEnv, stat) => stat match {
          case ValDef(name, tpt, Some(rhs)) =>
            val evalRef: Ref =
              if (stat.symbol.flags.isLazy)
                // do not factor out rhs from here (laziness)
                new Lazy(eval(rhs)(accEnv))
              else if (stat.symbol.flags.isMutable)
                new Var(eval(rhs)(accEnv))
              else
                new Eager(eval(rhs)(accEnv))

            accEnv.updated(stat.symbol, evalRef)
          case DefDef(_, _, _, _, _) =>
            // TODO: record the environment for closure purposes
            accEnv
          case stat =>
            eval(stat)(accEnv)
            accEnv
        })
        eval(expr)(newEnv)


      case Term.Literal(const) =>
        const.value

      case Term.Typed(expr, _) => eval(expr)

      case _ => throw new MatchError(tree.show)
    }
  }
}