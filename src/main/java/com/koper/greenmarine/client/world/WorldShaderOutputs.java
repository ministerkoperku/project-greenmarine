package com.koper.greenmarine.client.world;

import java.util.*;
import java.util.regex.Pattern;

/** Routes explicit pack fragment locations to the complete host pass attachment signature. */
final class WorldShaderOutputs {
    private static final Pattern OUTPUT=Pattern.compile("layout\\s*\\(([^)]*)\\)\\s*(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\s+)*out\\b");
    private static final Pattern LOCATION=Pattern.compile("\\blocation\\s*=\\s*([0-9]+)\\b");
    private static final Pattern DEPTH_OUTPUT=Pattern.compile("\\s*(?:(?:invariant|precise|highp|mediump|lowp)\\s+)*float\\s+gl_FragDepth\\s*;");
    static String remap(String shader,Map<Integer,Integer> locations) {
        if(locations.isEmpty())return shader;
        // Keep offsets stable while excluding comments from declaration discovery.
        String code=Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*").matcher(shader).replaceAll(match->" ".repeat(match.group().length()));
        var output=OUTPUT.matcher(code);var edits=new ArrayList<int[]>();var declared=new HashSet<Integer>();
        while(output.find()) {
            var location=LOCATION.matcher(output.group(1));
            if(!location.find()) {
                // Built-in depth is separate from the color attachment location namespace.
                if(DEPTH_OUTPUT.matcher(code).region(output.end(),code.length()).lookingAt())continue;
                throw new IllegalArgumentException("Named world fragment outputs require explicit locations");
            }
            int from=Integer.parseInt(location.group(1));var to=locations.get(from);
            if(to==null || !declared.add(from))throw new IllegalArgumentException("Undeclared or repeated world fragment output: "+from);
            edits.add(new int[]{output.start(1)+location.start(1),output.start(1)+location.end(1),to});
        }
        if(!declared.equals(locations.keySet()))throw new IllegalArgumentException("World fragment outputs do not match the declared named targets");
        var result=new StringBuilder(shader);
        for(int i=edits.size()-1;i>=0;i--) {var edit=edits.get(i);result.replace(edit[0],edit[1],Integer.toString(edit[2]));}
        return result.toString();
    }
}
