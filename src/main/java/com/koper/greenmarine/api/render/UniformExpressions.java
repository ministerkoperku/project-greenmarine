package com.koper.greenmarine.api.render;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.*;

/** Bounded, dependency-ordered CPU expressions for custom shader uniforms. No Minecraft or Lua
 * dependency. Evaluate once per host frame and reuse the result for its passes. State belongs to
 * one pack/world lifetime; call reset() on world/pack changes. Values are Boolean, Integer, Float,
 * or float[2..4]. Host matrices use column-major float[16] or arrays/lists of columns, matching
 * Iris's actual matrix component implementation (matrix.column.row). Exact dotted host keys are
 * also accepted, so an adapter need not allocate vector/matrix objects.
 *
 * Smooth half-life arguments use 0.1 seconds per unit, matching Iris 26.1 SmoothFloat source.
 * The first sample initializes directly; zero fade snaps, and zero delta preserves nonzero fades.
 * Unknown functions/inputs, invalid types/nonfinite results, and exhausted budgets are errors.
 */
public final class UniformExpressions {
    public enum Kind { VARIABLE, UNIFORM }
    public enum Type { BOOL, INT, FLOAT, VEC2, VEC3, VEC4 }
    public record Declaration(Kind kind, Type type, String name, String expression) {
        public Declaration {
            Objects.requireNonNull(kind); Objects.requireNonNull(type); Objects.requireNonNull(expression);
            if (name == null || !name.matches("[A-Za-z_][A-Za-z_0-9]*") || Set.of("true","false","pi").contains(name))
                throw error("Invalid or reserved declaration name: " + name);
        }
    }
    public record Limits(int declarations, int expressionCharacters, int nodes, int depth, int evaluationSteps) {
        public static final Limits DEFAULT = new Limits(1024, 16384, 16384, 128, 65536);
        public Limits {
            if (declarations<1 || declarations>4096 || expressionCharacters<1 || expressionCharacters>1_000_000 || nodes<1 || nodes>1_000_000
                || depth<1 || depth>256 || evaluationSteps<1 || evaluationSteps>1_000_000) throw new IllegalArgumentException("Invalid expression budgets");
        }
    }
    public static final class ExpressionException extends IllegalArgumentException {
        public ExpressionException(String message) { super(message); }
    }
    public record Evaluation(Map<String,Object> values, Map<String,Object> uniforms) {
        public Evaluation { values=copyValues(values); uniforms=copyValues(uniforms); }
    }
    public static final class State {
        private Object owner;
        private Map<Integer,double[]> smooth = new HashMap<>();
        public synchronized void reset() { owner=null; smooth.clear(); }
        public synchronized int smoothingSlots() { return smooth.size(); }
    }
    private record Compiled(Declaration declaration, Node expression, Set<String> references) {}

    public static final class Program {
        private final List<Declaration> declarations;
        private final List<Compiled> ordered;
        private final Set<String> required;
        private final Limits limits;
        private final Object stateOwner;
        private Program(List<Declaration> declarations,List<Compiled> ordered,Set<String> required,Limits limits) {
            this(declarations,ordered,required,limits,new Object());
        }
        private Program(List<Declaration> declarations,List<Compiled> ordered,Set<String> required,Limits limits,Object stateOwner) {
            this.declarations=List.copyOf(declarations);this.ordered=List.copyOf(ordered);
            this.required=Collections.unmodifiableSet(new LinkedHashSet<>(required));this.limits=limits;
            this.stateOwner=stateOwner;
        }
        public List<Declaration> declarations() { return declarations; }
        /** Full external reference strings, e.g. eyeBrightness.y or matrix.0.1; providing the root
         * vector/matrix instead is equivalent. Pack option and biome constants are external too. */
        public Set<String> requiredInputs() { return required; }
        public List<String> evaluationOrder() { return ordered.stream().map(c->c.declaration.name()).toList(); }
        /** Select outputs and their complete dependency closure without requiring unused host integrations.
         * Parsing/type-function validation still covers the original program. Selections share smooth
         * call-site identity so changing the selected passes does not reset retained smoothing.
         */
        public Program select(Set<String> outputs) {
            Objects.requireNonNull(outputs);
            var compiled=new LinkedHashMap<String,Compiled>();for(var item:ordered)compiled.put(item.declaration.name(),item);
            for(String name:outputs)if(!compiled.containsKey(name))throw error("Unknown declaration output: "+name);
            var selected=new ArrayList<Compiled>();var visiting=new LinkedHashSet<String>();var done=new HashSet<String>();var required=new TreeSet<String>();
            for(var item:ordered)if(outputs.contains(item.declaration.name()))order(item.declaration.name(),compiled,visiting,done,selected,required,limits.depth());
            if(done.size()==ordered.size())return this;
            return new Program(declarations.stream().filter(d->done.contains(d.name())).toList(),selected,required,limits,stateOwner);
        }
        public Evaluation evaluate(Map<String,?> inputs,State state,double deltaSeconds) {
            Objects.requireNonNull(inputs);
            return evaluateHost(inputs::get,state,deltaSeconds);
        }
        /** Suppliers are cached by key for the evaluation; no supplier runs repeatedly per reference. */
        public Evaluation evaluateSupplied(Map<String,? extends Supplier<?>> inputs,State state,double deltaSeconds) {
            Objects.requireNonNull(inputs);
            return evaluateHost(key->{var supplier=inputs.get(key);return supplier==null?null:supplier.get();},state,deltaSeconds);
        }
        private Evaluation evaluateHost(Function<String,Object> inputs,State state,double deltaSeconds) {
            Objects.requireNonNull(state);
            if (!Double.isFinite(deltaSeconds)||deltaSeconds<0) throw error("Delta seconds must be finite and nonnegative");
            synchronized(state) {
                var pending=new HashMap<Integer,double[]>();
                if (state.owner==stateOwner) state.smooth.forEach((key,value)->pending.put(key,value.clone()));
                var context=new Context(inputs,pending,deltaSeconds,limits.evaluationSteps());
                // Check every declared dependency, including inactive branches, so missing host
                // features cannot disappear merely because an expression currently takes another branch.
                for (String key:required) context.external(key);
                var uniforms=new LinkedHashMap<String,Object>();
                for (var compiled:ordered) {
                    try {
                        Object value=coerce(compiled.expression.eval(context),compiled.declaration.type());
                        context.values.put(compiled.declaration.name(),value);
                        if(compiled.declaration.kind()==Kind.UNIFORM) uniforms.put(compiled.declaration.name(),value);
                    } catch(ExpressionException e) { throw error(compiled.declaration.name()+": "+e.getMessage()); }
                }
                var result=new Evaluation(context.values,uniforms);
                state.owner=stateOwner;state.smooth=pending; // failed evaluations never commit partial state
                return result;
            }
        }
    }

    public static List<Declaration> declarations(Map<String,String> properties) {
        var result=new ArrayList<Declaration>();
        properties.forEach((key,value)->{
            if(!key.startsWith("uniform.")&&!key.startsWith("variable."))return;
            String[] parts=key.split("\\.",3);
            if(parts.length!=3)throw error("Invalid custom declaration: "+key);
            Type type;
            try {type=Type.valueOf(parts[1].toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw error("Unsupported declaration type: "+parts[1]);}
            result.add(new Declaration(parts[0].equals("uniform")?Kind.UNIFORM:Kind.VARIABLE,type,parts[2],value));
        });
        return List.copyOf(result);
    }
    public static Program compile(List<Declaration> declarations) { return compile(declarations,Limits.DEFAULT); }
    public static Program compile(List<Declaration> declarations,Set<String> allowedInputs) {
        var program=compile(declarations);
        for(String required:program.required) if(!allowedInputs.contains(required)&&!allowedInputs.contains(root(required)))
            throw error("Unknown input: "+required);
        return program;
    }
    public static Program compile(List<Declaration> declarations,Limits limits) {
        Objects.requireNonNull(declarations);Objects.requireNonNull(limits);
        if(declarations.size()>limits.declarations())throw error("Declaration budget exceeded");
        var compiled=new LinkedHashMap<String,Compiled>();var budget=new ParseBudget(limits);
        for(var declaration:declarations) {
            if(declaration.expression().length()>limits.expressionCharacters())throw error("Expression character budget exceeded: "+declaration.name());
            var parser=new Parser(declaration.expression(),budget);
            Node expression;
            try {expression=parser.parse();}catch(ExpressionException e){throw error(declaration.name()+": "+e.getMessage());}
            if(compiled.putIfAbsent(declaration.name(),new Compiled(declaration,expression,Set.copyOf(parser.references)))!=null)
                throw error("Duplicate declaration: "+declaration.name());
        }
        var ordered=new ArrayList<Compiled>();var visiting=new LinkedHashSet<String>();var done=new HashSet<String>();var required=new TreeSet<String>();
        for(String name:compiled.keySet())order(name,compiled,visiting,done,ordered,required,limits.depth());
        return new Program(declarations,ordered,required,limits);
    }
    private static void order(String name,Map<String,Compiled> compiled,Set<String> visiting,Set<String> done,List<Compiled> ordered,Set<String> required,int maxDepth) {
        if(done.contains(name))return;
        if(!visiting.add(name))throw error("Dependency cycle: "+String.join(" -> ",visiting)+" -> "+name);
        if(visiting.size()>maxDepth)throw error("Dependency depth budget exceeded");
        var current=compiled.get(name);
        for(String reference:current.references) {
            String base=root(reference);
            if(compiled.containsKey(base))order(base,compiled,visiting,done,ordered,required,maxDepth);else required.add(reference);
        }
        visiting.remove(name);done.add(name);ordered.add(current);
    }

    private static final class Context {
        final Function<String,Object> host;final Map<String,Object> hostCache=new HashMap<>(),values=new LinkedHashMap<>();
        final Map<Integer,double[]> smooth;final double delta;int steps;
        Context(Function<String,Object> host,Map<Integer,double[]> smooth,double delta,int steps){this.host=host;this.smooth=smooth;this.delta=delta;this.steps=steps;}
        void step(){if(--steps<0)throw error("Evaluation work budget exceeded");}
        Object read(String reference) {
            String base=root(reference);
            if(values.containsKey(base))return access(values.get(base),reference.substring(base.length()),reference);
            return external(reference);
        }
        Object host(String key){
            if(!hostCache.containsKey(key)) {
                try {hostCache.put(key,host.apply(key));}
                catch(RuntimeException failure){throw error("Host input "+key+": "+failure);}
            }
            return hostCache.get(key);
        }
        Object external(String reference) {
            step();
            Object exact=host(reference);
            if(exact!=null)return normalized(exact);
            String base=root(reference);Object source=host(base);
            if(source==null)throw error("Unknown host input: "+reference);
            return normalized(access(source,reference.substring(base.length()),reference));
        }
        Object smoothing(int slot,List<Node> arguments) {
            int offset=arguments.size()==4 || arguments.size()>1&&arguments.getFirst().constant()?1:0;
            if(offset==1)number(arguments.getFirst().eval(this)); // backward-compatible ID; Iris state is per call site
            Object target=arguments.get(offset).eval(this);
            double up=arguments.size()>offset+1?number(arguments.get(offset+1).eval(this)):1;
            double down=arguments.size()>offset+2?number(arguments.get(offset+2).eval(this)):up;
            if(up<0||down<0)throw error("Smooth half-lives must be nonnegative");
            double[] sample=components(target),old=smooth.get(slot),next=sample.clone();
            if(old!=null) {
                if(old.length!=sample.length)throw error("Smooth target changed dimensions");
                for(int i=0;i<sample.length;i++) {
                    double halfLife=(sample[i]>old[i]?up:down)*0.1;
                    next[i]=halfLife==0?sample[i]:old[i]+(sample[i]-old[i])*(-Math.expm1(-Math.log(2)*delta/halfLife));
                }
            }
            smooth.put(slot,next);
            return target instanceof double[]?next:finite(next[0]);
        }
    }
    private interface Node { Object eval(Context context); int depth(); boolean constant(); }
    private record Literal(Object value) implements Node {
        public Object eval(Context c){c.step();return value;}public int depth(){return 1;}public boolean constant(){return true;}
    }
    private record Reference(String name) implements Node {
        public Object eval(Context c){c.step();return c.read(name);}public int depth(){return 1;}public boolean constant(){return false;}
    }
    private record Unary(String operator,Node value) implements Node {
        public Object eval(Context c){c.step();Object v=value.eval(c);return switch(operator){case"!"->!bool(v);case"-"->map(v,x->-x);default->map(v,x->x);};}
        public int depth(){return value.depth()+1;}public boolean constant(){return value.constant();}
    }
    private record Binary(String operator,Node left,Node right) implements Node {
        public Object eval(Context c) {
            c.step();Object a=left.eval(c);
            if(operator.equals("&&"))return bool(a)&&bool(right.eval(c));
            if(operator.equals("||"))return bool(a)||bool(right.eval(c));
            Object b=right.eval(c);
            return switch(operator) {
                case"+"->zip(a,b,(x,y)->x+y);case"-"->zip(a,b,(x,y)->x-y);case"*"->zip(a,b,(x,y)->x*y);
                case"/"->zip(a,b,(x,y)->{if(y==0)throw error("Division by zero");return x/y;});
                case"%"->zip(a,b,(x,y)->{if(y==0)throw error("Remainder by zero");return x%y;});
                case"=="->equal(a,b);case"!="->!equal(a,b);case"<"->number(a)<number(b);case"<="->number(a)<=number(b);
                case">"->number(a)>number(b);case">="->number(a)>=number(b);default->throw error("Unknown operator: "+operator);
            };
        }
        public int depth(){return 1+Math.max(left.depth(),right.depth());}public boolean constant(){return left.constant()&&right.constant();}
    }
    private record Conditional(Node condition,Node yes,Node no) implements Node {
        public Object eval(Context c){c.step();return bool(condition.eval(c))?yes.eval(c):no.eval(c);}
        public int depth(){return 1+Math.max(condition.depth(),Math.max(yes.depth(),no.depth()));}
        public boolean constant(){return condition.constant()&&yes.constant()&&no.constant();}
    }
    private record Call(String name,List<Node> arguments,int slot) implements Node {
        public Object eval(Context c) {
            c.step();
            if(name.equals("smooth"))return c.smoothing(slot,arguments);
            if(name.equals("if")) {
                for(int i=0;i<arguments.size()-1;i+=2)if(bool(arguments.get(i).eval(c)))return arguments.get(i+1).eval(c);
                return arguments.getLast().eval(c);
            }
            var values=new ArrayList<Object>();for(var argument:arguments)values.add(argument.eval(c));
            return function(name,values);
        }
        public int depth(){return 1+arguments.stream().mapToInt(Node::depth).max().orElse(0);}
        public boolean constant(){return !name.equals("smooth")&&arguments.stream().allMatch(Node::constant);}
    }

    private static Object function(String name,List<Object> a) {
        Object first=a.getFirst();
        return switch(name) {
            case"vec2","vec3","vec4"->vector(Integer.parseInt(name.substring(3)),a);
            case"float"->finite(number(first));case"int"->integer(number(first));case"bool"->first instanceof Boolean?first:number(first)!=0;
            case"sin"->map(first,Math::sin);case"cos"->map(first,Math::cos);case"tan"->map(first,Math::tan);
            case"asin"->map(first,Math::asin);case"acos"->map(first,Math::acos);
            case"atan"->a.size()==1?map(first,Math::atan):zip(first,a.get(1),Math::atan2);case"atan2"->zip(first,a.get(1),Math::atan2);
            case"torad","radians"->map(first,Math::toRadians);case"todeg","degrees"->map(first,Math::toDegrees);
            case"exp"->map(first,Math::exp);case"exp2"->map(first,x->Math.pow(2,x));case"exp10"->map(first,x->Math.pow(10,x));
            case"log"->a.size()==1?map(first,Math::log):zip(first,a.get(1),(base,value)->Math.log(value)/Math.log(base));
            case"log2"->map(first,x->Math.log(x)/Math.log(2));case"log10"->map(first,Math::log10);
            case"pow"->zip(first,a.get(1),Math::pow);case"sqrt"->map(first,Math::sqrt);
            case"abs"->map(first,Math::abs);case"sign","signum"->map(first,Math::signum);
            case"floor"->map(first,Math::floor);case"ceil"->map(first,Math::ceil);case"round"->map(first,x->Math.floor(x+0.5));
            case"frac"->map(first,x->x-Math.floor(x));
            case"min","max"->{Object v=first;for(int i=1;i<a.size();i++)v=zip(v,a.get(i),name.equals("min")?Math::min:Math::max);yield v;}
            case"clamp"->zip(zip(first,a.get(1),Math::max),a.get(2),Math::min);
            case"mix"->zip(first,zip(zip(a.get(1),first,(x,y)->x-y),a.get(2),(x,y)->x*y),(x,y)->x+y);
            case"edge","step"->zip(first,a.get(1),(edge,value)->value<edge?0:1);
            case"fmod"->zip(first,a.get(1),(x,y)->{if(y==0)throw error("Floor modulus by zero");return x-y*Math.floor(x/y);});
            case"between"->number(first)>=number(a.get(1))&&number(first)<=number(a.get(2));
            case"equals"->Math.abs(number(first)-number(a.get(1)))<=number(a.get(2));
            case"in"->{boolean found=false;for(int i=1;i<a.size();i++)if(equal(first,a.get(i))){found=true;break;}yield found;}
            default->throw error("Unknown function: "+name);
        };
    }
    private static void validateCall(String name,int count,List<Node> arguments) {
        boolean valid=switch(name) {
            case"vec2","vec3","vec4"->count>=1&&count<=4;
            case"if"->count>=3&&count<=33&&count%2==1;
            case"in","min","max"->count>=2&&count<=32;
            case"smooth"->count>=1&&count<=4&&(count!=4||arguments.getFirst().constant());
            case"atan","log"->count==1||count==2;
            case"atan2","pow","fmod","edge","step"->count==2;
            case"clamp","mix","between","equals"->count==3;
            case"float","int","bool","sin","cos","tan","asin","acos","torad","radians","todeg","degrees","exp","exp2","exp10","log2","log10","sqrt","abs","sign","signum","floor","ceil","round","frac"->count==1;
            default->throw error("Unknown function: "+name);
        };
        if(!valid)throw error("Invalid arguments for "+name+": "+count);
    }

    private static final class ParseBudget {final Limits limits;int nodes,slots;ParseBudget(Limits limits){this.limits=limits;}}
    private static final class Parser {
        final String source;final ParseBudget budget;final Set<String> references=new LinkedHashSet<>();int at,nesting;
        Parser(String source,ParseBudget budget){this.source=source;this.budget=budget;}
        Node node(Node n){if(++budget.nodes>budget.limits.nodes())throw error("AST node budget exceeded");if(n.depth()>budget.limits.depth())throw error("AST depth budget exceeded");return n;}
        Node parse(){Node n=conditional();skip();if(at!=source.length())throw error("Unexpected token at "+at);return n;}
        Node conditional(){Node n=binary(0);if(eat("?")){enter();Node yes=conditional();need(":");Node no=conditional();nesting--;n=node(new Conditional(n,yes,no));}return n;}
        private static final String[][] OPERATORS={{"||"},{"&&"},{"==","!="},{"<=",">=","<",">"},{"+","-"},{"*","/","%"}};
        Node binary(int level){if(level==OPERATORS.length)return unary();Node n=binary(level+1);while(true){String op=null;for(String candidate:OPERATORS[level])if(eat(candidate)){op=candidate;break;}if(op==null)return n;n=node(new Binary(op,n,binary(level+1)));}}
        Node unary(){for(String op:List.of("!","-","+"))if(eat(op)){enter();Node n=node(new Unary(op,unary()));nesting--;return n;}return primary();}
        Node primary(){
            skip();if(eat("(")){enter();Node n=conditional();need(")");nesting--;return n;}
            if(at>=source.length())throw error("Expected expression at "+at);
            char c=source.charAt(at);
            if(Character.isDigit(c)||c=='.'&&at+1<source.length()&&Character.isDigit(source.charAt(at+1))){
                int start=at;
                while(at<source.length()&&Character.isDigit(source.charAt(at)))at++;
                if(at<source.length()&&source.charAt(at)=='.'){at++;while(at<source.length()&&Character.isDigit(source.charAt(at)))at++;}
                if(at<source.length()&&(source.charAt(at)=='e'||source.charAt(at)=='E')){at++;if(at<source.length()&&(source.charAt(at)=='+'||source.charAt(at)=='-'))at++;int exponent=at;while(at<source.length()&&Character.isDigit(source.charAt(at)))at++;if(at==exponent)throw error("Invalid exponent");}
                String token=source.substring(start,at);if(at<source.length()&&(source.charAt(at)=='f'||source.charAt(at)=='F'))at++;
                try{return node(new Literal(finite(Double.parseDouble(token))));}catch(NumberFormatException e){throw error("Invalid number: "+token);}
            }
            if(!Character.isLetter(c)&&c!='_')throw error("Unexpected character at "+at+": "+c);
            int start=at++;while(at<source.length()&&(Character.isLetterOrDigit(source.charAt(at))||source.charAt(at)=='_'))at++;
            String name=source.substring(start,at);
            if(eat("(")){
                enter();var arguments=new ArrayList<Node>();if(!eat(")")){do{if(arguments.size()>=33)throw error("Function argument budget exceeded");arguments.add(conditional());}while(eat(","));need(")");}nesting--;
                validateCall(name,arguments.size(),arguments);return node(new Call(name,List.copyOf(arguments),budget.slots++));
            }
            if(name.equals("true")||name.equals("false"))return node(new Literal(Boolean.valueOf(name)));
            if(name.equals("pi"))return node(new Literal(Math.PI));
            while(eat(".")){skip();if(at>=source.length())throw error("Missing component");char component=source.charAt(at++);if("xyzwrgba stpq0123".indexOf(component)<0||component==' ')throw error("Invalid component: "+component);name+="."+component;}
            references.add(name);return node(new Reference(name));
        }
        void enter(){if(++nesting>budget.limits.depth())throw error("Parser depth budget exceeded");}
        void skip(){while(at<source.length()&&Character.isWhitespace(source.charAt(at)))at++;}
        boolean eat(String token){skip();if(!source.startsWith(token,at))return false;if(token.length()==1&&at+1<source.length()&&"!<>".contains(token)&&source.charAt(at+1)=='=')return false;at+=token.length();return true;}
        void need(String token){if(!eat(token))throw error("Expected '"+token+"' at "+at);}
    }

    private static Object access(Object value,String suffix,String reference) {
        if(suffix.isEmpty())return normalized(value);
        String[] components=suffix.substring(1).split("\\.");
        if(components.length==2&&value.getClass().isArray()&&Array.getLength(value)==16&&!value.getClass().componentType().isArray()) {
            int column=component(components[0]),row=component(components[1]);return normalized(Array.get(value,column*4+row));
        }
        Object current=value;
        for(String part:components) {
            int index=component(part);
            if(current!=null&&current.getClass().isArray()) {if(index>=Array.getLength(current))throw error("Component outside input: "+reference);current=Array.get(current,index);}
            else if(current instanceof List<?> list){if(index>=list.size())throw error("Component outside input: "+reference);current=list.get(index);}
            else throw error("Input is not a vector/matrix: "+reference);
        }
        return normalized(current);
    }
    private static int component(String part) {
        if(part.length()!=1)throw error("Swizzling is unsupported: "+part);
        return switch(part.charAt(0)){case'x','r','s','0'->0;case'y','g','t','1'->1;case'z','b','p','2'->2;case'w','a','q','3'->3;default->throw error("Invalid component: "+part);};
    }
    private static Object normalized(Object value) {
        if(value instanceof Boolean)return value;
        if(value instanceof Number number)return finite(number.doubleValue());
        if(value instanceof List<?> list){if(list.size()<2||list.size()>4)throw error("Host vector must have 2–4 components");double[] result=new double[list.size()];for(int i=0;i<result.length;i++)result[i]=number(list.get(i));return result;}
        if(value!=null&&value.getClass().isArray()){int size=Array.getLength(value);if(size<2||size>4)throw error("Host vector must have 2–4 components");double[] result=new double[size];for(int i=0;i<size;i++)result[i]=number(Array.get(value,i));return result;}
        throw error("Unsupported or missing host value: "+(value==null?"null":value.getClass().getSimpleName()));
    }
    private static Object coerce(Object value,Type type) {
        return switch(type) {
            case BOOL->bool(value);case INT->integer(number(value));case FLOAT->{float result=(float)number(value);if(!Float.isFinite(result))throw error("Float overflow");yield result;}
            case VEC2,VEC3,VEC4->{int size=type.ordinal()-Type.VEC2.ordinal()+2;double[] vector=components(value);if(!(value instanceof double[])||vector.length!=size)throw error("Expected "+type);float[] result=new float[size];for(int i=0;i<size;i++){result[i]=(float)vector[i];if(!Float.isFinite(result[i]))throw error("Vector overflow");}yield result;}
        };
    }
    private static Object vector(int size,List<Object> arguments) {
        if(arguments.size()==1&&!(arguments.getFirst() instanceof double[])){double[] result=new double[size];Arrays.fill(result,number(arguments.getFirst()));return result;}
        var values=new ArrayList<Double>();for(Object argument:arguments)for(double component:components(argument))values.add(component);
        if(values.size()!=size)throw error("Vector constructor requires "+size+" components");
        double[] result=new double[size];for(int i=0;i<size;i++)result[i]=values.get(i);return result;
    }
    private static Object map(Object value,DoubleUnaryOperator function){if(value instanceof double[] vector){double[] result=new double[vector.length];for(int i=0;i<result.length;i++)result[i]=finite(function.applyAsDouble(vector[i]));return result;}return finite(function.applyAsDouble(number(value)));}
    private static Object zip(Object a,Object b,DoubleBinaryOperator function) {
        if(a instanceof double[]||b instanceof double[]) {
            double[] x=components(a),y=components(b);int size=Math.max(x.length,y.length);
            if(x.length!=1&&x.length!=size||y.length!=1&&y.length!=size)throw error("Vector dimensions differ");
            double[] result=new double[size];for(int i=0;i<size;i++)result[i]=finite(function.applyAsDouble(x[x.length==1?0:i],y[y.length==1?0:i]));return result;
        }
        return finite(function.applyAsDouble(number(a),number(b)));
    }
    private static double[] components(Object value){return value instanceof double[] vector?vector:new double[]{number(value)};}
    private static double number(Object value){if(!(value instanceof Number number))throw error("Expected scalar number");return finite(number.doubleValue());}
    private static int integer(double value){if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw error("Integer overflow");return (int)value;}
    private static double finite(double value){if(!Double.isFinite(value))throw error("Nonfinite expression value");return value;}
    private static boolean bool(Object value){if(!(value instanceof Boolean result))throw error("Expected boolean");return result;}
    private static boolean equal(Object a,Object b){if(a instanceof Boolean||b instanceof Boolean)return bool(a)==bool(b);if(a instanceof double[]||b instanceof double[])return a instanceof double[] x&&b instanceof double[] y&&Arrays.equals(x,y);return number(a)==number(b);}
    private static String root(String reference){int dot=reference.indexOf('.');return dot<0?reference:reference.substring(0,dot);}
    private static Map<String,Object> copyValues(Map<String,Object> values){var copy=new LinkedHashMap<String,Object>();values.forEach((key,value)->copy.put(key,value instanceof float[] vector?vector.clone():value));return Collections.unmodifiableMap(copy);}
    private static ExpressionException error(String message){return new ExpressionException(message);}
    private UniformExpressions() {}
}
