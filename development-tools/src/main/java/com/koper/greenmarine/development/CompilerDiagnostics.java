package com.koper.greenmarine.development;

import java.util.*;
import java.util.regex.Pattern;

/** Shaderc prints {@code name:line: error: text}; lines are already remapped to the editor source by ShaderCompiler. */
public final class CompilerDiagnostics {
    public record Issue(int line, boolean error, String message) {}
    private static final Pattern LINE = Pattern.compile("^(.*?):(\\d+):(?: \\[in ([^\\]]+)\\])? (error|warning): (.*)$");
    private CompilerDiagnostics() {}

    public static List<Issue> parse(String diagnostics, String name) {
        if (diagnostics == null || diagnostics.isBlank()) return List.of();
        var issues = new ArrayList<Issue>();
        for (String raw : diagnostics.split("\n")) {
            var matcher = LINE.matcher(raw.strip());
            if (!matcher.matches() || (name != null && !matcher.group(1).equals(name))) continue;
            String message = matcher.group(3) == null ? matcher.group(5) : matcher.group(5) + " (in " + matcher.group(3) + ")";
            issues.add(new Issue(Integer.parseInt(matcher.group(2)), matcher.group(4).equals("error"), message));
            if (issues.size() >= 256) break;
        }
        return List.copyOf(issues);
    }
    /** First error wins per line; warnings only fill lines without errors. */
    public static Map<Integer, Issue> byLine(List<Issue> issues) {
        var lines = new TreeMap<Integer, Issue>();
        for (var issue : issues) {
            var existing = lines.get(issue.line());
            if (existing == null || (issue.error() && !existing.error())) lines.put(issue.line(), issue);
        }
        return lines;
    }
}
