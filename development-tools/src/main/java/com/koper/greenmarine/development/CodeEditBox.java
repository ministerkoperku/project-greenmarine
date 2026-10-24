package com.koper.greenmarine.development;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.TextCursorUtils;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.util.Map;
import java.util.function.Consumer;

/** MultiLineEditBox with a line gutter, GLSL colors and compiler marks. Vanilla's box can't be subclassed for drawing. */
public final class CodeEditBox extends AbstractTextAreaWidget {
    private static final int LINE = 9, GUTTER_PAD = 4, CURSOR = 0xFFD0D0D0, ERROR = 0xFFFF6B6B, WARNING = 0xFFFFC66B, NUMBER = 0xFF707070;
    private final Font font;
    private final Field field;
    private final int gutter;
    private Consumer<String> listener = value -> {};
    private Map<Integer, CompilerDiagnostics.Issue> issues = Map.of();
    private String colored;
    private int[] colors = new int[0];
    private long focusedTime = Util.getMillis();

    public CodeEditBox(Font font, int x, int y, int width, int height, Component narration) {
        super(x, y, width, height, narration, AbstractScrollArea.defaultSettings(LINE / 2), true, false);
        this.font = font;
        gutter = font.width("00000") + GUTTER_PAD;
        field = new Field(font, Math.max(16, width - totalInnerPadding() - gutter));
        field.setCursorListener(this::scrollToCursor);
        field.setValueListener(value -> { colored = null; listener.accept(value); });
    }
    public void setCharacterLimit(int limit) { field.setCharacterLimit(limit); }
    public void setValueListener(Consumer<String> listener) { this.listener = listener; }
    public void setValue(String value) { field.setValue(value, true); }
    public String getValue() { return field.value(); }
    public void setIssues(Map<Integer, CompilerDiagnostics.Issue> issues) { this.issues = Map.copyOf(issues); }

    public void goToLine(int line) {
        String value = field.value(); int index = 0;
        for (int current = 1; current < line && index >= 0; current++) { index = value.indexOf('\n', index); if (index >= 0) index++; }
        field.seekCursor(Whence.ABSOLUTE, index < 0 ? value.length() : index);
        scrollToCursor();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!isFocused()) return false;
        if (event.key() == 258 && !event.hasShiftDown()) { field.insertText("    "); return true; }
        if (event.key() == 257 || event.key() == 335) {
            String value = field.value(); int cursor = field.cursor();
            int start = value.lastIndexOf('\n', cursor - 1) + 1, indent = start;
            while (indent < cursor && (value.charAt(indent) == ' ' || value.charAt(indent) == '\t')) indent++;
            String prefix = value.substring(start, indent);
            if (value.substring(start, cursor).stripTrailing().endsWith("{")) prefix += "    ";
            field.insertText("\n" + prefix);
            return true;
        }
        return field.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) {
        if (!visible || !isFocused() || !event.isAllowedChatCharacter()) return false;
        String typed = event.codepointAsString();
        if (typed.equals("}")) {
            // closing brace on a blank indented line steps back one level
            String value = field.value(); int cursor = field.cursor(), start = value.lastIndexOf('\n', cursor - 1) + 1;
            if (cursor - start >= 4 && value.substring(start, cursor).isBlank()) field.deleteText(-4);
        }
        field.insertText(typed);
        return true;
    }
    @Override public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (doubleClick) field.selectWordAtCursor();
        else { field.setSelecting(event.hasShiftDown()); seek(event.x(), event.y()); }
    }
    @Override protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        field.setSelecting(true); seek(event.x(), event.y()); field.setSelecting(event.hasShiftDown());
    }
    private void seek(double x, double y) {
        field.seekCursorToPoint(x - getX() - innerPadding() - gutter, y - getY() - innerPadding() + scrollAmount());
    }
    @Override public boolean capturesInput() { return visible && isFocused(); }
    @Override public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) focusedTime = Util.getMillis();
        Minecraft.getInstance().onTextInputFocusChange(this, focused);
    }
    @Override public int getInnerHeight() { return LINE * field.getLineCount(); }
    @Override public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.editBox", getMessage(), getValue()));
    }

    @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        String value = field.value();
        if (colored != value) { colors = GlslHighlighter.colors(value); colored = value; }
        int cursor = field.cursor(), top = getInnerTop(), left = getInnerLeft(), textLeft = left + gutter;
        boolean showCursor = isFocused() && TextCursorUtils.isCursorVisible(Util.getMillis() - focusedTime);
        int cursorX = textLeft, cursorY = top, logical = 0;
        int[] selection = field.hasSelection() ? field.selection() : null;
        int[] lines = field.ranges();
        for (int row = 0; row < lines.length; row += 2) {
            int begin = lines[row], end = lines[row + 1];
            boolean startsLine = begin == 0 || value.charAt(begin - 1) == '\n';
            if (startsLine) logical++;
            if (withinContentAreaTopBottom(top, top + LINE)) {
                var issue = startsLine ? issues.get(logical) : null;
                if (issue != null) graphics.fill(left - 2, top, getX() + width - innerPadding(), top + LINE, issue.error() ? 0x40FF3030 : 0x30FFB020);
                if (startsLine) {
                    String number = Integer.toString(logical);
                    graphics.text(font, number, textLeft - GUTTER_PAD - font.width(number), top, issue == null ? NUMBER : issue.error() ? ERROR : WARNING, false);
                }
                if (selection != null && selection[0] <= end && selection[1] >= begin) {
                    int from = font.width(value.substring(begin, Math.max(begin, selection[0])));
                    int to = font.width(value.substring(begin, Math.min(end, selection[1])));
                    graphics.textHighlight(textLeft + from, top, textLeft + Math.max(to, from + 2), top + LINE, true);
                }
                int x = textLeft;
                for (int run = begin; run < end;) {
                    int color = colors[run], next = run + 1;
                    while (next < end && colors[next] == color) next++;
                    String part = value.substring(run, next);
                    graphics.text(font, part, x, top, color, false);
                    x += font.width(part); run = next;
                }
            }
            if (cursor >= begin && cursor <= end && (cursor < end || end == value.length() || value.charAt(end) == '\n')) {
                cursorX = textLeft + font.width(value.substring(begin, cursor)); cursorY = top;
            }
            top += LINE;
        }
        if (showCursor && withinContentAreaTopBottom(cursorY, cursorY + LINE)) TextCursorUtils.extractInsertCursor(graphics, cursorX, cursorY, CURSOR, LINE + 1);
        if (capturesInput()) Minecraft.getInstance().textInputManager().setTextInputArea(cursorX, cursorY, cursorX + 1, cursorY + LINE + 1);
    }

    private void scrollToCursor() {
        double scroll = scrollAmount();
        if (field.cursor() <= field.lineBegin((int) (scroll / LINE))) scroll = field.getLineAtCursor() * LINE;
        else {
            if (field.cursor() > field.lineEnd(Math.max(0, (int) ((scroll + height) / LINE) - 1))) scroll = field.getLineAtCursor() * LINE - height + LINE + totalInnerPadding();
        }
        setScrollAmount(scroll);
    }

    /** StringView is protected, so only a subclass can hand out wrapped line ranges. */
    private static final class Field extends MultilineTextField {
        Field(Font font, int width) { super(font, width); }
        int[] ranges() {
            var out = new int[getLineCount() * 2]; int i = 0;
            for (var view : iterateLines()) { if (i + 1 >= out.length) break; out[i++] = view.beginIndex(); out[i++] = view.endIndex(); }
            return i == out.length ? out : java.util.Arrays.copyOf(out, i);
        }
        int[] selection() { var view = getSelected(); return new int[] {view.beginIndex(), view.endIndex()}; }
        int lineBegin(int line) { return getLineView(line).beginIndex(); }
        int lineEnd(int line) { return getLineView(line).endIndex(); }
    }
}
