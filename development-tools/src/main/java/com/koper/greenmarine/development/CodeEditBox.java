package com.koper.greenmarine.development;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
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
    private Field field;
    private final int gutter;
    private Consumer<String> listener = value -> {};
    private Map<Integer, CompilerDiagnostics.Issue> issues = Map.of();
    private String colored;
    private int[] colors = new int[0];
    private java.util.List<ShaderCompletions.Choice> suggestions=java.util.List.of();
    private int suggestionIndex;
    private boolean dismissed,readOnly;
    private int caretX,caretY;
    private CompletionPopup.Layout popup;
    private java.util.function.BiFunction<String,Integer,java.util.List<ShaderCompletions.Choice>> completionSource=ShaderCompletions::suggest;
    private long focusedTime = Util.getMillis();

    public CodeEditBox(Font font, int x, int y, int width, int height, Component narration) {
        super(x, y, width, height, narration, AbstractScrollArea.defaultSettings(LINE / 2), true, false);
        this.font = font;
        gutter = font.width("00000") + GUTTER_PAD;
        field = new Field(font, Math.max(16, width - totalInnerPadding() - gutter));
        listenToField();
    }
    private void listenToField(){
        field.setCursorListener(()->{scrollToCursor();refreshSuggestions();});
        field.setValueListener(value -> { colored = null; dismissed=false;refreshSuggestions();listener.accept(value); });
    }
    /** Rewrap inside the same focused widget; Android text input must not restart on each drag event. */
    void resizeEditor(int x,int y,int width,int height){
        boolean rewrap=width!=getWidth();
        setRectangle(width,height,x,y);
        if(rewrap){
            String value=field.value();int cursor=field.cursor(),limit=field.characterLimit();
            int anchor=cursor;
            if(field.hasSelection()){int[] selection=field.selection();anchor=cursor==selection[0]?selection[1]:selection[0];}
            field=new Field(font,Math.max(16,width-totalInnerPadding()-gutter));
            field.setCharacterLimit(limit);field.setValue(value,true);
            field.seekCursor(Whence.ABSOLUTE,anchor);field.setSelecting(true);
            field.seekCursor(Whence.ABSOLUTE,cursor);field.setSelecting(false);
            listenToField();
        }
        popup=null;refreshScrollAmount();
    }
    public void setCharacterLimit(int limit) { field.setCharacterLimit(limit); }
    public void setValueListener(Consumer<String> listener) { this.listener = listener; }
    public void setValue(String value) { field.setValue(value, true); }
    public String getValue() { return field.value(); }
    public void setIssues(Map<Integer, CompilerDiagnostics.Issue> issues) { this.issues = Map.copyOf(issues); }

    private void refreshSuggestions(){
        if(dismissed){suggestions=java.util.List.of();return;}
        suggestions=readOnly?java.util.List.of():completionSource.apply(field.value(),field.cursor());
        suggestionIndex=Math.clamp(suggestionIndex,0,Math.max(0,suggestions.size()-1));
    }
    public void setCompletionSource(java.util.function.BiFunction<String,Integer,java.util.List<ShaderCompletions.Choice>> source){completionSource=source;refreshSuggestions();}
    public void setReadOnly(boolean readOnly){this.readOnly=readOnly;if(readOnly){suggestions=java.util.List.of();popup=null;}}
    public void insertSnippet(String text){if(!readOnly)field.insertText(text);}
    public CompletionPopup.Layout popupBounds(){return popup;}
    public void extractCompletionPopup(GuiGraphicsExtractor g,int mouseX,int mouseY){
        popup=null;
        if(!visible||!isFocused()||readOnly||suggestions.isEmpty())return;
        popup=CompletionPopup.layout(caretX,caretY,g.guiWidth(),g.guiHeight(),suggestions.size());
        int first=Math.max(0,suggestionIndex-popup.rows()+1);
        g.fill(popup.x(),popup.y(),popup.x()+popup.width(),popup.y()+popup.height(),0xff142338);
        g.outline(popup.x(),popup.y(),popup.width(),popup.height(),StudioUi.ACCENT);
        for(int i=0;i<popup.rows();i++){
            int index=first+i;if(index>=suggestions.size())break;int y=popup.y()+i*16;
            if(index==suggestionIndex)g.fill(popup.x()+1,y+1,popup.x()+popup.width()-1,y+16,0xff254c48);
            g.text(font,font.plainSubstrByWidth(suggestions.get(index).label(),popup.width()-12),popup.x()+6,y+4,index==suggestionIndex?StudioUi.ACCENT:StudioUi.TEXT,false);
        }
        int y=popup.y()+popup.rows()*16+4;String help=suggestions.get(suggestionIndex).help();
        String line=font.plainSubstrByWidth(help,popup.width()-12);
        g.text(font,line,popup.x()+6,y,StudioUi.MUTED,false);
        if(line.length()<help.length())g.text(font,font.plainSubstrByWidth(help.substring(line.length()).stripLeading(),popup.width()-12),popup.x()+6,y+10,StudioUi.MUTED,false);
    }
    public boolean clickCompletion(MouseButtonEvent event){
        if(popup==null||event.button()!=InputConstants.MOUSE_BUTTON_LEFT)return false;
        if(event.x()<popup.x()||event.x()>=popup.x()+popup.width()||event.y()<popup.y()||event.y()>=popup.y()+popup.height())return false;
        int row=(int)(event.y()-popup.y())/16;
        if(row<popup.rows()){suggestionIndex=Math.min(suggestions.size()-1,Math.max(0,suggestionIndex-popup.rows()+1)+row);acceptCompletion();}
        return true;
    }
    public void dismissCompletions(){dismissed=true;suggestions=java.util.List.of();popup=null;}
    public String completionLabel(){return suggestions.isEmpty()?"Type a name for suggestions":suggestions.get(suggestionIndex).label()+"  "+(suggestionIndex+1)+"/"+suggestions.size();}
    public String completionHelp(){return suggestions.isEmpty()?"Tab: indent  |  Ctrl+Space: suggest  |  Esc: dismiss":suggestions.get(suggestionIndex).help();}
    public boolean hasCompletion(){return !suggestions.isEmpty();}
    public void nextCompletion(int direction){if(!suggestions.isEmpty())suggestionIndex=Math.floorMod(suggestionIndex+direction,suggestions.size());}
    public void acceptCompletion(){
        if(suggestions.isEmpty())return;
        var choice=suggestions.get(suggestionIndex);
        var edit=ShaderCompletions.accept(field.value(),field.cursor(),choice);
        field.setSelecting(true);field.seekCursor(Whence.ABSOLUTE,ShaderCompletions.start(field.value(),field.cursor()));
        field.insertText(choice.insert());
        // Use the exact edit for an existing opening parenthesis and place the caret inside a new call.
        if(!field.value().equals(edit.text()))field.setValue(edit.text(),true);
        field.setSelecting(false);field.seekCursor(Whence.ABSOLUTE,edit.cursor());
        dismissed=true;suggestions=java.util.List.of();popup=null;
    }

    public int cursorPosition(){return field.cursor();}
    public void moveCursor(int cursor){field.setSelecting(false);field.seekCursor(Whence.ABSOLUTE,Math.clamp(cursor,0,field.value().length()));}
    public void goToLine(int line) {
        String value = field.value(); int index = 0;
        for (int current = 1; current < line && index >= 0; current++) { index = value.indexOf('\n', index); if (index >= 0) index++; }
        field.seekCursor(Whence.ABSOLUTE, index < 0 ? value.length() : index);
        scrollToCursor();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!isFocused()||!visible) return false;
        if(readOnly){
            if(event.isCopy()||event.isSelectAll()||java.util.Set.of(InputConstants.KEY_UP,InputConstants.KEY_DOWN,InputConstants.KEY_LEFT,InputConstants.KEY_RIGHT,InputConstants.KEY_HOME,InputConstants.KEY_END,InputConstants.KEY_PAGEUP,InputConstants.KEY_PAGEDOWN).contains(event.key()))return field.keyPressed(event);
            return false;
        }
        if(event.key()==InputConstants.KEY_SPACE&&event.hasControlDown()){dismissed=false;refreshSuggestions();return true;}
        if(event.key()==InputConstants.KEY_ESCAPE&&!suggestions.isEmpty()){dismissed=true;suggestions=java.util.List.of();return true;}
        if(!suggestions.isEmpty()){
            if(event.key()==InputConstants.KEY_DOWN){nextCompletion(1);return true;}
            if(event.key()==InputConstants.KEY_UP){nextCompletion(-1);return true;}
            if((event.key()==InputConstants.KEY_TAB&&!event.hasShiftDown())||event.key()==InputConstants.KEY_RETURN||event.key()==InputConstants.KEY_NUMPADENTER){acceptCompletion();return true;}
        }
        if (event.key() == InputConstants.KEY_TAB && !event.hasShiftDown()) { field.insertText("    "); return true; }
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
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
        if (readOnly || !visible || !isFocused() || !event.isAllowedChatCharacter()) return false;
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
        Minecraft.getInstance().onTextInputFocusChange(this, focused&&!readOnly);
    }
    @Override public int getInnerHeight() { return LINE * field.getLineCount(); }
    @Override public void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.editBox", getMessage(), getValue()));
    }

    @Override protected void extractBackground(GuiGraphicsExtractor graphics){
        graphics.fill(getX(),getY(),getRight(),getBottom(),StudioUi.BG);
        graphics.outline(getX(),getY(),width,height,isFocused()?StudioUi.ACCENT:StudioUi.BORDER);
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
        caretX=cursorX;caretY=cursorY;
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
