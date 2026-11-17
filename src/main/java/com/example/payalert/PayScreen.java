package com.example.payalert;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Pay Alert settings. Open in game with U. */
public class PayScreen extends Screen {

    public PayScreen() {
        super(Text.literal("Pay Alert settings"));
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "OFF";
    }

    private void row(int x, int y, int w, String label, Runnable action) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
            action.run();
            PayAlertMod.saveConfig();
            MinecraftClient.getInstance().setScreen(new PayScreen());
        }).dimensions(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        PayAlertMod.Config c = PayAlertMod.cfg;
        int w = 230;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - 90;
        int step = 22;
        int r = 0;

        row(x, y + step * r++, w, "Alerts: " + onOff(c.enabled), () -> PayAlertMod.cfg.enabled = !PayAlertMod.cfg.enabled);

        this.addDrawableChild(new ValueSlider(x, y + step * r++, w, 20, "Size", 0.5, 3.0, c.hudScale,
                v -> {
                    PayAlertMod.cfg.hudScale = Math.round(v * 20) / 20.0;
                    PayAlertMod.saveConfig();
                },
                v -> Math.round(v * 20) * 5 + "%"));

        this.addDrawableChild(new ValueSlider(x, y + step * r++, w, 20, "Height from top", 0, 200, c.hudY,
                v -> {
                    PayAlertMod.cfg.hudY = (int) Math.round(v);
                    PayAlertMod.saveConfig();
                },
                v -> Math.round(v) + " px"));

        this.addDrawableChild(new ValueSlider(x, y + step * r++, w, 20, "Show for", 2, 30, c.seconds,
                v -> {
                    PayAlertMod.cfg.seconds = Math.round(v);
                    PayAlertMod.saveConfig();
                },
                v -> Math.round(v) + " sec"));

        row(x, y + step * r++, w, "Unmatched amounts: " + (c.showUnmatched ? "show name + amount" : "hide"),
                () -> PayAlertMod.cfg.showUnmatched = !PayAlertMod.cfg.showUnmatched);

        row(x, y + step * r++, w, "Also read player chat: " + onOff(c.listenToPlayerChat),
                () -> PayAlertMod.cfg.listenToPlayerChat = !PayAlertMod.cfg.listenToPlayerChat);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Edit games / amounts..."),
                b -> MinecraftClient.getInstance().setScreen(new RulesScreen(this))).dimensions(x, y + step * r++, w, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Test alert (5.1M)"),
                b -> PayAlertMod.handle("TestPlayer paid you $5.1M")).dimensions(x, y + step * r++, w, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(x, y + step * r + 4, w, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        String title = "Pay Alert settings";
        ctx.drawTextWithShadow(this.textRenderer, title,
                this.width / 2 - this.textRenderer.getWidth(title) / 2, this.height / 2 - 108, 0xFFFFFFFF);
        String note = "Message text: config/payalert.json";
        ctx.drawTextWithShadow(this.textRenderer, note,
                this.width / 2 - this.textRenderer.getWidth(note) / 2, this.height / 2 + 112, 0xFFAAAAAA);
    }

    private static class ValueSlider extends SliderWidget {
        private final String label;
        private final double min;
        private final double max;
        private final DoubleConsumer onChange;
        private final DoubleFunction<String> fmt;

        ValueSlider(int x, int y, int w, int h, String label, double min, double max, double current,
                    DoubleConsumer onChange, DoubleFunction<String> fmt) {
            super(x, y, w, h, Text.empty(), Math.max(0.0, Math.min(1.0, (current - min) / (max - min))));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            this.fmt = fmt;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (fmt == null) return;
            setMessage(Text.literal(label + ": " + fmt.apply(min + this.value * (max - min))));
        }

        @Override
        protected void applyValue() {
            onChange.accept(min + this.value * (max - min));
        }
    }
}
