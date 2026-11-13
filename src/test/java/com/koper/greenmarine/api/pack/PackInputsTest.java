package com.koper.greenmarine.api.pack;

import com.google.gson.*;
import com.koper.greenmarine.api.render.UniformExpressions;
import com.koper.greenmarine.api.render.UniformLayout;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PackInputsTest {
    private static PackInputs parse(String json){return PackInputs.parse(JsonParser.parseString(json).getAsJsonObject());}
    private static UniformExpressions.Declaration expression(String name,String expression) {
        return new UniformExpressions.Declaration(UniformExpressions.Kind.UNIFORM,UniformExpressions.Type.FLOAT,name,expression);
    }
    @Test void absentAndEmptyInputsHaveNoConstantsOrExpressions() {
        assertSame(PackInputs.NONE,PackInputs.parse(null));
        var inputs=parse("{}");
        assertTrue(inputs.constants().isEmpty());assertTrue(inputs.expressions().isEmpty());assertEquals(0,inputs.sunPathRotation());
        assertTrue(inputs.program().requiredInputs().isEmpty());
    }
    @Test void parsesDeclaredTypesAndEvaluatesRealDependentExpressions() {
        var inputs=parse("""
            {"constants":{"SEA_LEVEL":62,"flags":[true,false],"weights":[1,2,3],"cameraMatrix":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]},
             "sunPathRotation":-35,
             "expressions":[
               {"kind":"uniform","type":"float","name":"height","expression":"level * 2"},
               {"kind":"variable","type":"float","name":"level","expression":"SEA_LEVEL + eyeAltitude"},
               {"kind":"uniform","type":"bool","name":"enabled","expression":"flags.x"},
               {"kind":"uniform","type":"int","name":"count","expression":"3"},
               {"kind":"uniform","type":"vec2","name":"pair","expression":"vec2(weights.x, weights.z)"}]}
            """);
        assertEquals(-35,inputs.sunPathRotation());assertSame(inputs.program(),inputs.program());
        assertEquals(5,inputs.expressions().size());assertEquals(UniformExpressions.Kind.VARIABLE,inputs.expressions().get(1).kind());
        var host=new HashMap<String,Object>(inputs.constants());host.put("eyeAltitude",3);
        var evaluation=inputs.program().evaluate(host,new UniformExpressions.State(),0);
        assertEquals(130f,evaluation.uniforms().get("height"));assertEquals(true,evaluation.uniforms().get("enabled"));
        assertEquals(3,evaluation.uniforms().get("count"));assertArrayEquals(new float[]{1,3},(float[])evaluation.uniforms().get("pair"));
        assertEquals(Set.of("SEA_LEVEL","eyeAltitude","flags.x","weights.x","weights.z"),inputs.program().requiredInputs());
    }
    @Test void constantsAndDeclarationsAreDefensiveImmutableSnapshots() {
        var vector=new float[]{1,2,3};var flags=new boolean[]{true,false};var ints=new int[]{7,8};
        var list=new ArrayList<>(List.of(4,5));
        var values=new LinkedHashMap<String,Object>();values.put("vector",vector);values.put("flags",flags);values.put("ints",ints);values.put("list",list);
        var declarations=new ArrayList<>(List.of(expression("gain","vector.x")));
        var inputs=new PackInputs(values,declarations,15);
        vector[0]=99;flags[0]=false;ints[0]=99;list.clear();values.clear();declarations.clear();
        ((float[])inputs.constants().get("vector"))[0]=98;((boolean[])inputs.constants().get("flags"))[0]=false;((int[])inputs.constants().get("ints"))[0]=98;
        assertArrayEquals(new float[]{1,2,3},(float[])inputs.constants().get("vector"));
        assertArrayEquals(new boolean[]{true,false},(boolean[])inputs.constants().get("flags"));assertArrayEquals(new int[]{7,8},(int[])inputs.constants().get("ints"));
        assertEquals(List.of(4,5),inputs.constants().get("list"));assertEquals(1,inputs.expressions().size());
        assertThrows(UnsupportedOperationException.class,()->inputs.constants().clear());
        assertThrows(UnsupportedOperationException.class,()->((List<?>)inputs.constants().get("list")).clear());
        assertThrows(UnsupportedOperationException.class,()->inputs.expressions().clear());
    }
    @Test void preciseJsonNumbersSurviveUnsignedLayoutValidation() {
        var layout=UniformLayout.parse("uint value;");
        var input=parse("{\"constants\":{\"limit\":4294967295}}");
        assertInstanceOf(BigDecimal.class,input.constants().get("limit"));
        assertEquals(-1,layout.pack(Map.of("value",input.constants().get("limit"))).getInt(0));
        for(String number:List.of("4294967295.0000000000000000001","4294967296","-0.0000000000000000001")) {
            var values=parse("{\"constants\":{\"limit\":"+number+"}}").constants();
            assertThrows(IllegalArgumentException.class,()->layout.pack(Map.of("value",values.get("limit"))),number);
        }
        var tiny=parse("{\"constants\":{\"tiny\":1e-400}}").constants().get("tiny");
        assertEquals(new BigDecimal("1e-400"),tiny);
    }
    @Test void rejectsUnknownKeysAndWrongJsonTypesWithoutCoercion() {
        for(String json:List.of(
            "{\"unknown\":1}","{\"constants\":[]}","{\"constants\":null}","{\"constants\":{\"x\":\"1\"}}",
            "{\"constants\":{\"x\":null}}","{\"constants\":{\"x\":{}}}","{\"constants\":{\"x\":[[1]]}}",
            "{\"expressions\":{}}","{\"expressions\":[null]}","{\"expressions\":[3]}",
            "{\"sunPathRotation\":\"-35\"}","{\"sunPathRotation\":true}","{\"sunPathRotation\":null}",
            "{\"expressions\":[{\"kind\":\"uniform\",\"type\":\"float\",\"name\":\"x\",\"expression\":\"1\",\"extra\":0}]}",
            "{\"expressions\":[{\"kind\":\"uniform\",\"type\":\"float\",\"name\":\"x\"}]}",
            "{\"expressions\":[{\"kind\":\"UNIFORM\",\"type\":\"float\",\"name\":\"x\",\"expression\":\"1\"}]}",
            "{\"expressions\":[{\"kind\":\"uniform\",\"type\":\"mat4\",\"name\":\"x\",\"expression\":\"1\"}]}",
            "{\"expressions\":[{\"kind\":\"uniform\",\"type\":\"float\",\"name\":\"x\",\"expression\":1}]}"))
            assertThrows(IllegalArgumentException.class,()->parse(json),json);
    }
    @Test void rejectsInvalidNamesCollisionsAndInvalidProgramsAtConstruction() {
        for(String name:List.of("","a.b","1name","gl_reserved","true","pi"))
            assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of(name,1),List.of(),0),name);
        assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of("gain",1),List.of(expression("gain","1")),0));
        assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of(),List.of(expression("a","b"),expression("b","a")),0));
        assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of(),List.of(expression("a","unknownFunction(1)")),0));
    }
    @Test void rejectsNonfiniteUnsupportedAndMutableConstantValues() {
        for(Object value:List.of(Float.NaN,Double.POSITIVE_INFINITY,new double[]{1,Double.NEGATIVE_INFINITY},
            new AtomicInteger(1),new char[]{'a'},new Object[]{1},Map.of("value",1),new int[][]{{1}},List.of(List.of(1)),new BigDecimal("1e400")))
            assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of("value",value),List.of(),0),value.toString());
        for(float rotation:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of(),List.of(),rotation));
        var nullValue=new HashMap<String,Object>();nullValue.put("value",null);
        assertThrows(IllegalArgumentException.class,()->new PackInputs(nullValue,List.of(),0));
    }
    @Test void boundsConstantKeysArraysAndTotalScalarComponents() {
        var keys=new LinkedHashMap<String,Object>();for(int i=0;i<256;i++)keys.put("k"+i,1);
        assertEquals(256,new PackInputs(keys,List.of(),0).constants().size());keys.put("overflow",1);
        assertThrows(IllegalArgumentException.class,()->new PackInputs(keys,List.of(),0));
        assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of("huge",new float[16385]),List.of(),0));
        assertThrows(IllegalArgumentException.class,()->new PackInputs(Map.of("empty",new float[0]),List.of(),0));
        var components=new LinkedHashMap<String,Object>();for(int i=0;i<4;i++)components.put("v"+i,new int[16384]);
        assertEquals(4,new PackInputs(components,List.of(),0).constants().size());components.put("overflow",1);
        assertThrows(IllegalArgumentException.class,()->new PackInputs(components,List.of(),0));
    }
    @Test void jsonParsingEnforcesTheSameComponentAndNumericBudgets() {
        var object=new JsonObject();var constants=new JsonObject();object.add("constants",constants);
        for(int n=0;n<4;n++){var array=new JsonArray();for(int i=0;i<16384;i++)array.add(i);constants.add("a"+n,array);}
        assertEquals(4,PackInputs.parse(object).constants().size());constants.addProperty("excess",1);
        assertThrows(IllegalArgumentException.class,()->PackInputs.parse(object));
        for(String json:List.of("{\"sunPathRotation\":1e100}","{\"constants\":{\"v\":1e400}}"))
            assertThrows(IllegalArgumentException.class,()->parse(json),json);
        var booleanRotation=new JsonObject();booleanRotation.addProperty("sunPathRotation",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->PackInputs.parse(booleanRotation));
    }
    @Test void copiesAllAcceptedPrimitiveArrayKindsAndExactNumericTypes() {
        var values=new LinkedHashMap<String,Object>();
        values.put("bytes",new byte[]{1,2});values.put("shorts",new short[]{3,4});values.put("longs",new long[]{5,6});values.put("doubles",new double[]{7,8});
        values.put("precise",new BigDecimal("0.10000000000000000001"));values.put("large",new BigInteger("9007199254740993"));
        var inputs=new PackInputs(values,List.of(),0);
        ((byte[])values.get("bytes"))[0]=0;((short[])values.get("shorts"))[0]=0;((long[])values.get("longs"))[0]=0;((double[])values.get("doubles"))[0]=0;
        assertArrayEquals(new byte[]{1,2},(byte[])inputs.constants().get("bytes"));assertArrayEquals(new short[]{3,4},(short[])inputs.constants().get("shorts"));
        assertArrayEquals(new long[]{5,6},(long[])inputs.constants().get("longs"));assertArrayEquals(new double[]{7,8},(double[])inputs.constants().get("doubles"));
        assertEquals(new BigDecimal("0.10000000000000000001"),inputs.constants().get("precise"));assertEquals(new BigInteger("9007199254740993"),inputs.constants().get("large"));
    }
}
