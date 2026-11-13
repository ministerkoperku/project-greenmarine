package com.koper.greenmarine.api.render;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UniformExpressionsTest {
    private static UniformExpressions.Program compile(String... declarations) {
        var properties = new LinkedHashMap<String,String>();
        for (int i=0;i<declarations.length;i+=2) properties.put(declarations[i],declarations[i+1]);
        return UniformExpressions.compile(UniformExpressions.declarations(properties));
    }
    @Test void forwardDependenciesUseDeclaredTypesAndOnlyUniformsAreUploaded() {
        var program=compile("uniform.vec3.color","vec3(light, light * 2.0, fmod(-3.0,2.0))",
            "variable.float.light","gain + 1.0", "uniform.int.frame","frameCounter % 4", "uniform.bool.active","in(category,1,3) && !hurt");
        var result=program.evaluate(Map.of("gain",0.5f,"frameCounter",7,"category",3,"hurt",false),new UniformExpressions.State(),0);
        assertArrayEquals(new float[]{1.5f,3.0f,1.0f},(float[])result.uniforms().get("color"));
        assertEquals(3,result.uniforms().get("frame")); assertEquals(true,result.uniforms().get("active"));
        assertTrue(result.values().containsKey("light")); assertFalse(result.uniforms().containsKey("light"));
        assertEquals(Set.of("gain","frameCounter","category","hurt"),program.requiredInputs());
    }
    @Test void matrixAccessMatchesIrisColumnThenRowAndVectorsAcceptComponentAliases() {
        var program=compile("uniform.vec4.result","vec4(matrix.1.0, matrix.0.1, eyeBrightness.y, position.b)");
        float[][] columns={{1,2,3,4},{5,6,7,8},{9,10,11,12},{13,14,15,16}};
        var values=program.evaluate(Map.of("matrix",columns,"eyeBrightness",new int[]{32,240},"position",new float[]{2,3,4}),new UniformExpressions.State(),0).uniforms();
        assertArrayEquals(new float[]{5,2,240,4},(float[])values.get("result"));
        var flat=program.evaluate(Map.of("matrix",new float[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16},
            "eyeBrightness.y",240,"position.b",4),new UniformExpressions.State(),0);
        assertArrayEquals(new float[]{5,2,240,4},(float[])flat.uniforms().get("result"));
    }
    @Test void branchesShortCircuitAndVectorArithmeticKeepsDimensions() {
        var p=compile("uniform.float.selected","if(false, 1.0 / 0.0, true, 6.0, 7.0)",
            "uniform.bool.safe","false && 1.0 / 0.0 > 0.0 || true", "uniform.vec3.vector","clamp(vec3(-2,2,9) * 0.5 + 1.0,0.0,3.0)");
        var result=p.evaluate(Map.of(),new UniformExpressions.State(),0).uniforms();
        assertEquals(6f,result.get("selected")); assertEquals(true,result.get("safe"));
        assertArrayEquals(new float[]{0,2,3},(float[])result.get("vector"));
    }
    @Test void cyclesUnknownFunctionsAndUnknownInputsAreExplicitErrors() {
        assertThrows(UniformExpressions.ExpressionException.class,()->compile("uniform.float.a","b + 1","variable.float.b","a + 1"));
        assertThrows(UniformExpressions.ExpressionException.class,()->compile("uniform.float.a","madeUp(2)"));
        var p=compile("uniform.float.a","missing + 1");
        assertThrows(UniformExpressions.ExpressionException.class,()->p.evaluate(Map.of(),new UniformExpressions.State(),0));
        assertThrows(UniformExpressions.ExpressionException.class,()->UniformExpressions.compile(p.declarations(),Set.of("other")));
        assertThrows(UniformExpressions.ExpressionException.class,()->compile("uniform.vec2.v","vec2(1,2).xy"));
    }
    @Test void smoothUsesIrisSourceHalfLivesAndResetsOnExplicitLifecycle() {
        var p=compile("uniform.float.fade","smooth(target,1.0,2.0)");var state=new UniformExpressions.State();
        assertEquals(0f,p.evaluate(Map.of("target",0f),state,0).uniforms().get("fade"));
        assertEquals(0.5f,(Float)p.evaluate(Map.of("target",1f),state,0.1).uniforms().get("fade"),1e-6f);
        assertEquals(0.25f,(Float)p.evaluate(Map.of("target",0f),state,0.2).uniforms().get("fade"),1e-6f);
        state.reset(); assertEquals(1f,p.evaluate(Map.of("target",1f),state,0).uniforms().get("fade"));
        var replacement=compile("uniform.float.fade","smooth(target,1.0,2.0)");
        assertEquals(8f,replacement.evaluate(Map.of("target",8f),state,0).uniforms().get("fade"));
    }
    @Test void smoothDependsOnElapsedTimeRatherThanNumberOfCallsAndPreservesPause() {
        var p=compile("uniform.float.fade","smooth(target,1.0,1.0)");var one=new UniformExpressions.State();var two=new UniformExpressions.State();
        p.evaluate(Map.of("target",0f),one,0);p.evaluate(Map.of("target",0f),two,0);
        float a=(Float)p.evaluate(Map.of("target",1f),one,0.1).uniforms().get("fade");
        p.evaluate(Map.of("target",1f),two,0.05);
        float b=(Float)p.evaluate(Map.of("target",1f),two,0.05).uniforms().get("fade");
        assertEquals(a,b,1e-6f);
        assertEquals(b,(Float)p.evaluate(Map.of("target",0f),two,0).uniforms().get("fade"),1e-6f);
    }
    @Test void failedEvaluationDoesNotAdvanceAnySmoothingState() {
        var p=compile("variable.float.fade","smooth(target,1.0,1.0)","uniform.float.result","fade / denominator");
        var state=new UniformExpressions.State();p.evaluate(Map.of("target",0f,"denominator",1f),state,0);
        assertThrows(UniformExpressions.ExpressionException.class,()->p.evaluate(Map.of("target",1f,"denominator",0f),state,0.1));
        assertEquals(0.5f,(Float)p.evaluate(Map.of("target",1f,"denominator",1f),state,0.1).uniforms().get("result"),1e-6f);
    }
    @Test void hostSuppliersAreReadOnceForAnEvaluationAndBadValuesAreRejected() {
        var p=compile("uniform.float.sum","host + host");var reads=new AtomicInteger();
        var result=p.evaluateSupplied(Map.of("host",()->{reads.incrementAndGet();return 2f;}),new UniformExpressions.State(),0);
        assertEquals(4f,result.uniforms().get("sum"));assertEquals(1,reads.get());
        assertThrows(UniformExpressions.ExpressionException.class,()->p.evaluate(Map.of("host",Float.NaN),new UniformExpressions.State(),0));
        assertThrows(UniformExpressions.ExpressionException.class,()->compile("uniform.bool.a","1.0").evaluate(Map.of(),new UniformExpressions.State(),0));
    }
    @Test void parsingAndExecutionBudgetsFailBeforeUnboundedWork() {
        var declarations=UniformExpressions.declarations(Map.of("uniform.float.a","1 + 2 + 3"));
        assertThrows(UniformExpressions.ExpressionException.class,()->UniformExpressions.compile(declarations,new UniformExpressions.Limits(10,100,2,16,100)));
        var p=UniformExpressions.compile(declarations,new UniformExpressions.Limits(10,100,100,16,2));
        assertThrows(UniformExpressions.ExpressionException.class,()->p.evaluate(Map.of(),new UniformExpressions.State(),0));
        assertThrows(UniformExpressions.ExpressionException.class,()->compile("uniform.float.a","1 +"));
    }
    @Test void hostResolutionIsAlsoBoundedByTheWorkBudget() {
        var declarations=UniformExpressions.declarations(Map.of("uniform.float.a","first + second + third"));
        var p=UniformExpressions.compile(declarations,new UniformExpressions.Limits(10,100,100,16,1));
        var calls=new AtomicInteger();
        java.util.function.Supplier<Object> supplier=()->{calls.incrementAndGet();return 1f;};
        assertThrows(UniformExpressions.ExpressionException.class,()->p.evaluateSupplied(Map.of("first",supplier,"second",supplier,"third",supplier),new UniformExpressions.State(),0));
        assertEquals(1,calls.get(),"Host suppliers must not run after the work budget is consumed");
    }
    private static UniformExpressions.Program select(UniformExpressions.Program program,Set<String> outputs) throws Exception {
        var method=UniformExpressions.Program.class.getMethod("select",Set.class);
        return (UniformExpressions.Program)method.invoke(program,outputs);
    }
    @Test void selectedOutputResolvesOnlyItsDependencyClosure() throws Exception {
        var program=compile("uniform.float.visible","base * 2", "variable.float.base","host + 1",
            "uniform.float.optional","dhFarPlane");
        var selected=select(program,Set.of("visible"));
        assertEquals(Set.of("host"),selected.requiredInputs());
        assertEquals(List.of("base","visible"),selected.evaluationOrder());
        assertEquals(8f,selected.evaluate(Map.of("host",3),new UniformExpressions.State(),0).uniforms().get("visible"));
        assertTrue(select(program,Set.of()).requiredInputs().isEmpty());
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->select(program,Set.of("missing")));
    }
    @Test void changingSelectedOutputsPreservesSmoothingFromTheSameProgram() throws Exception {
        var program=compile("uniform.float.fade","smooth(target,10.0,10.0)","uniform.float.other","extra");
        var state=new UniformExpressions.State();
        select(program,Set.of("fade")).evaluate(Map.of("target",0),state,0);
        assertEquals(.5f,(Float)select(program,Set.of("fade")).evaluate(Map.of("target",1),state,1).uniforms().get("fade"),1e-6f);
        assertEquals(.75f,(Float)select(program,Set.of("fade","other")).evaluate(Map.of("target",1,"extra",5),state,1).uniforms().get("fade"),1e-6f);
    }
}
