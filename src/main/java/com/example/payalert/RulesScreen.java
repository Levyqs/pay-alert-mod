package com.example.payalert;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;

/** Edit the games: name, smallest amount, biggest amount (in millions) and what the decimals must be. */
public class RulesScreen extends Screen {
    private static final int MAX_ROWS = 8;
    private final Screen parent;

    public RulesScreen(Screen parent) {
        super(Text.literal("Games and amounts"));
        this.parent = parent;
    }

    private TextFieldWidget field(int x, int y, int w, String value, java.util.function.Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.empty());
        f.setMaxLength(40);
        f.setText(value == null ? "" : value);
        f.setChangedListener(s -> {
            onChange.accept(s);
            PayAlertMod.saveConfig();
        });
        this.addDrawableChild(f);
        return f;
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void init() {
        List<PayAlertMod.Rule> rules = PayAlertMod.cfg.rules;
        int left = this.width / 2 - 170;
        int top = this.height / 2 - 90;

        for (int i = 0; i < rules.size() && i < MAX_ROWS; i++) {
            PayAlertMod.Rule r = rules.get(i);
            int y = top + i * 22;
            field(left, y, 116, r.label, s -> r.label = s);
            field(left + 120, y, 46, num(r.min), s -> r.min = parse(s, r.min));
            field(left + 170, y, 46, num(r.max), s -> r.max = parse(s, r.max));
            field(left + 220, y, 50, r.decimals, s -> r.decimals = s.trim());
            this.addDrawableChild(ButtonWidget.builder(Text.literal(r.enabled ? "ON" : "OFF"), b -> {
                r.enabled = !r.enabled;
                PayAlertMod.saveConfig();
                MinecraftClient.getInstance().setScreen(new RulesScreen(parent));
            }).dimensions(left + 274, y - 1, 36, 20).build());
        }

        int by = top + Math.min(rules.size(), MAX_ROWS) * 22 + 6;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Add game"), b -> {
            if (rules.size() < MAX_ROWS) rules.add(new PayAlertMod.Rule("New game", 1, 5, "any", ""));
            PayAlertMod.saveConfig();
            MinecraftClient.getInstance().setScreen(new RulesScreen(parent));
        }).dimensions(left, by, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Remove last"), b -> {
            if (!rules.isEmpty()) rules.remove(rules.size() - 1);
            PayAlertMod.saveConfig();
            MinecraftClient.getInstance().setScreen(new RulesScreen(parent));
        }).dimensions(left + 104, by, 100, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(left + 208, by, 102, 20).build());
    }

    @Override
    public void close() {
        PayAlertMod.saveConfig();
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int left = this.width / 2 - 170;
        int top = this.height / 2 - 90;
        ctx.drawTextWithShadow(this.textRenderer, "Games and amounts (first match wins, amounts in millions)",
                this.width / 2 - 165, top - 28, 0xFFFFFFFF);
        ctx.drawTextWithShadow(this.textRenderer, "Game name", left, top - 12, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "From", left + 120, top - 12, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "To", left + 170, top - 12, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "Ends in", left + 220, top - 12, 0xFFAAAAAA);
        String hint = "Ends in: any, none (whole number), or digits like 1 for 5.1 / 2 for 5.2";
        ctx.drawTextWithShadow(this.textRenderer, hint, this.width / 2 - this.textRenderer.getWidth(hint) / 2,
                this.height / 2 + 118, 0xFF888888);
    }
}
