package com.koper.greenmarine.development.iris;

import java.util.*;

/**
 * Object-like #define/#if evaluator for shaders.properties and option discovery. GLSL itself goes
 * through glslang; this only has to agree with it on conditionals, not on token pasting.
 */
public final class MiniPreprocessor {
    private final Map<String, String> macros;
    private int evalDepth;

    public MiniPreprocessor(Map<String, String> macros) { this.macros = new LinkedHashMap<>(macros); }
    public Map<String, String> macros() { return Collections.unmodifiableMap(macros); }

    /** Returns active non-directive lines; continuation backslashes are joined first. */
    public List<String> process(String text) { return process(text, false); }

    /**
     * Resolves conditionals but keeps active #define/#undef and other directives for a real preprocessor to expand.
     * Redefinitions get an #undef first: OptiFine/Iris accept them, glslang refuses.
     */
    public String resolveConditionals(String text) { return String.join("\n", process(text, true)); }

    private List<String> process(String text, boolean keepDirectives) {
        var out = new ArrayList<String>();
        var stack = new ArrayDeque<boolean[]>(); // {branchActive, anyBranchTaken, parentActive}
        boolean active = true;
        for (String line : joinContinuations(text)) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("#")) { out.add(active ? line : ""); continue; }
            String directive = trimmed.substring(1).strip();
            String word = directive.split("[\\s(]", 2)[0], rest = directive.substring(word.length()).strip();
            rest = stripComment(rest);
            switch (word) {
                case "ifdef", "ifndef" -> {
                    boolean value = macros.containsKey(rest.split("\\s+")[0]) == word.equals("ifdef");
                    stack.push(new boolean[] {active && value, value, active}); active = active && value;
                }
                case "if" -> {
                    boolean value = active && truthy(rest);
                    stack.push(new boolean[] {value, value, active}); active = value;
                }
                case "elif" -> {
                    if (stack.isEmpty()) continue;
                    var top = stack.peek();
                    boolean value = top[2] && !top[1] && truthy(rest);
                    top[0] = value; top[1] |= value; active = value;
                }
                case "else" -> {
                    if (stack.isEmpty()) continue;
                    var top = stack.peek();
                    top[0] = top[2] && !top[1]; top[1] = true; active = top[0];
                }
                case "endif" -> { if (!stack.isEmpty()) active = stack.pop()[2]; }
                case "define" -> {
                    if (!active || rest.isEmpty()) continue;
                    int split = 0;
                    while (split < rest.length() && (Character.isLetterOrDigit(rest.charAt(split)) || rest.charAt(split) == '_')) split++;
                    String name = rest.substring(0, split);
                    // function-like macros only matter as "defined" here
                    if (keepDirectives) { if (macros.containsKey(name)) out.add("#undef " + name); out.add(line); }
                    macros.put(name, split < rest.length() && rest.charAt(split) == '(' ? "1" : rest.substring(split).strip());
                    continue;
                }
                case "undef" -> { if (active) { macros.remove(rest.split("\\s+")[0]); if (keepDirectives) out.add(line); } }
                default -> { if (active) out.add(line); }
            }
            if (keepDirectives && !word.equals("undef")) out.add("");
        }
        return out;
    }
    private static String stripComment(String text) {
        int line = text.indexOf("//"), block = text.indexOf("/*");
        int cut = line < 0 ? block : block < 0 ? line : Math.min(line, block);
        return cut < 0 ? text : text.substring(0, cut).strip();
    }
    private static List<String> joinContinuations(String text) {
        var lines = new ArrayList<String>(); var current = new StringBuilder();
        for (String raw : text.split("\r?\n", -1)) {
            if (raw.endsWith("\\")) { current.append(raw, 0, raw.length() - 1).append(' '); continue; }
            current.append(raw); lines.add(current.toString()); current.setLength(0);
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    public boolean truthy(String expression) {
        try { return new Expr(expression).parse() != 0; }
        catch (RuntimeException e) { return false; }
    }

    private final class Expr {
        private final String s; private int i;
        Expr(String s) { this.s = s; }
        double parse() { double v = or(); skip(); if (i < s.length()) throw new IllegalArgumentException("Trailing: " + s.substring(i)); return v; }
        private double or() { double v = and(); while (eat("||")) { double r = and(); v = (v != 0 || r != 0) ? 1 : 0; } return v; }
        private double and() { double v = cmp(); while (eat("&&")) { double r = cmp(); v = (v != 0 && r != 0) ? 1 : 0; } return v; }
        private double cmp() {
            double v = add();
            while (true) {
                if (eat("==")) v = v == add() ? 1 : 0; else if (eat("!=")) v = v != add() ? 1 : 0;
                else if (eat("<=")) v = v <= add() ? 1 : 0; else if (eat(">=")) v = v >= add() ? 1 : 0;
                else if (eat("<")) v = v < add() ? 1 : 0; else if (eat(">")) v = v > add() ? 1 : 0;
                else return v;
            }
        }
        private double add() { double v = mul(); while (true) { if (eat("+")) v += mul(); else if (peek('-')) { i++; v -= mul(); } else return v; } }
        private double mul() { double v = unary(); while (true) { if (eat("*")) v *= unary(); else if (eat("/")) { double d = unary(); v = d == 0 ? 0 : v / d; } else if (eat("%")) { double d = unary(); v = d == 0 ? 0 : v % d; } else return v; } }
        private double unary() {
            if (eat("!")) return unary() == 0 ? 1 : 0;
            if (eat("-")) return -unary();
            if (eat("+")) return unary();
            return primary();
        }
        private double primary() {
            skip();
            if (eat("(")) { double v = or(); if (!eat(")")) throw new IllegalArgumentException("Missing )"); return v; }
            int start = i;
            if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                String number = s.substring(start, i).replaceAll("[fFuUlL]+$", "");
                return number.startsWith("0x") ? Long.parseLong(number.substring(2), 16) : Double.parseDouble(number);
            }
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
            String name = s.substring(start, i);
            if (name.isEmpty()) throw new IllegalArgumentException("Unexpected " + s.substring(i));
            if (name.equals("defined")) {
                boolean paren = eat("("); skip();
                int n = i; while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                boolean value = macros.containsKey(s.substring(n, i));
                if (paren && !eat(")")) throw new IllegalArgumentException("Missing ) after defined");
                return value ? 1 : 0;
            }
            String value = macros.get(name);
            if (value == null || value.isBlank()) return 0;
            if (++evalDepth > 32) throw new IllegalArgumentException("Macro recursion");
            try { return new Expr(value).parse(); } catch (RuntimeException e) { return 0; } finally { evalDepth--; }
        }
        private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private boolean peek(char c) { skip(); return i < s.length() && s.charAt(i) == c && !(i + 1 < s.length() && (s.charAt(i + 1) == '=' || s.charAt(i + 1) == c)); }
        private boolean eat(String token) {
            skip();
            if (!s.startsWith(token, i)) return false;
            // keep "<" from eating "<=" and "!" from eating "!="
            if (token.length() == 1 && i + 1 < s.length() && "<>!=".indexOf(token.charAt(0)) >= 0 && s.charAt(i + 1) == '=') return false;
            if ((token.equals("&") || token.equals("|")) && i + 1 < s.length() && s.charAt(i + 1) == token.charAt(0)) return false;
            i += token.length(); return true;
        }
    }
}
