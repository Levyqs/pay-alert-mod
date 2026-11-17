package com.example.payalert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pay Alert: reads "Name paid you $250M" messages and shows who paid, how much,
 * and which game the amount is for. Every rule is editable in game (press U).
 */
public class PayAlertMod implements ClientModInitializer {

    // ------------------------------------------------------------------ config

    public static class Rule {
        public boolean enabled = true;
        /** Text shown big under the message, for example "Paper game". */
        public String label = "";
        /** Smallest amount in millions. */
        public double min = 0;
        /** Largest amount in millions. */
        public double max = 0;
        /** "any", "none" (whole numbers only), or the decimal digits, for example "1" for 5.1 or "25" for 5.25. */
        public String decimals = "any";
        /** Optional item shown at the bottom, for example "minecraft:elytra". */
        public String icon = "";

        public Rule() {
        }

        Rule(String label, double min, double max, String decimals, String icon) {
            this.label = label;
            this.min = min;
            this.max = max;
            this.decimals = decimals;
            this.icon = icon;
        }
    }

    public static class Config {
        public boolean enabled = true;
        /** Needs 3 groups: 1 = name, 2 = number, 3 = k/m/b/t suffix. */
        public String payPattern = "(?i)(\\w{1,16})\\s+paid\\s+you\\s*[^0-9\\s]*\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt]?)";
        /** {name}, {amount} and {game} can be used. */
        public String messageTemplate = "{name} has paid you {amount}";
        public boolean showUnmatched = true;
        public String unmatchedLabel = "";
        /** Also react to normal player chat. Off by default so players cannot fake a payment by typing it. */
        public boolean listenToPlayerChat = false;
        public double hudScale = 1.5;
        public int hudY = 24;
        public double seconds = 8;
        public List<Rule> rules = defaultRules();
    }

    static List<Rule> defaultRules() {
        List<Rule> r = new ArrayList<>();
        r.add(new Rule("Elytra game", 350, 350, "none", "minecraft:elytra"));
        r.add(new Rule("Blackjack", 5, 25.2, "1", ""));
        r.add(new Rule("Paper game", 5, 25.2, "2", ""));
        r.add(new Rule("Arrow game", 10, 50, "none", ""));
        return r;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Config cfg = new Config();
    private static Pattern payPattern;

    static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("payalert.json");
    }

    static void loadConfig() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                Config loaded = GSON.fromJson(reader, Config.class);
                if (loaded != null) cfg = loaded;
            } catch (Exception e) {
                System.err.println("[PayAlert] could not read config: " + e);
            }
        }
        if (cfg.rules == null) cfg.rules = defaultRules();
        if (cfg.payPattern == null || cfg.payPattern.isEmpty()) cfg.payPattern = new Config().payPattern;
        if (cfg.messageTemplate == null) cfg.messageTemplate = "{name} has paid you {amount}";
        compilePattern();
        saveConfig();
    }

    static void saveConfig() {
        try (Writer w = Files.newBufferedWriter(configPath())) {
            GSON.toJson(cfg, w);
        } catch (Exception e) {
            System.err.println("[PayAlert] could not save config: " + e);
        }
    }

    static void compilePattern() {
        try {
            payPattern = Pattern.compile(cfg.payPattern);
        } catch (Exception e) {
            payPattern = Pattern.compile(new Config().payPattern);
        }
    }

    // ------------------------------------------------------------------- state

    private static KeyBinding settingsKey;
    private static int tickNow = 0;
    private static String lastRaw = "";
    private static int lastRawTick = -100;

    private static int ticksLeft = 0;
    private static String shownLine = "";
    private static String shownGame = "";
    private static ItemStack shownIcon = null;

    // -------------------------------------------------------------------- init

    @Override
    public void onInitializeClient() {
        loadConfig();
        settingsKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.payalert.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, KeyBinding.Category.MISC));

        // Server messages such as "Name paid you $250M" arrive as game messages.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) onText(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            if (cfg.listenToPlayerChat) onText(message.getString());
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            tickNow++;
            if (ticksLeft > 0) ticksLeft--;
            while (settingsKey.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new PayScreen());
            }
        });
        HudElementRegistry.addLast(Identifier.of("payalert", "hud"), this::renderHud);
    }

    // ----------------------------------------------------------------- parsing

    static void onText(String raw) {
        if (!cfg.enabled || raw == null) return;
        // The same line can arrive twice (game + chat), so ignore an identical line within 5 ticks.
        if (raw.equals(lastRaw) && tickNow - lastRawTick < 5) return;
        lastRaw = raw;
        lastRawTick = tickNow;
        handle(raw);
    }

    /** Used by the Test button too. */
    static void handle(String raw) {
        if (payPattern == null) compilePattern();
        Matcher m = payPattern.matcher(raw);
        if (!m.find() || m.groupCount() < 3) return;

        String name = m.group(1);
        String numText = m.group(2).replace(",", "");
        String suffix = m.group(3) == null ? "" : m.group(3).toLowerCase();

        BigDecimal number;
        try {
            number = new BigDecimal(numText);
        } catch (Exception e) {
            return;
        }
        double millions = toMillions(number, suffix);
        String fraction = fractionOf(numText);
        String amountText = numText + suffix.toUpperCase();

        Rule hit = null;
        for (Rule r : cfg.rules) {
            if (r == null || !r.enabled) continue;
            if (matches(r, millions, fraction)) {
                hit = r;
                break;
            }
        }

        String game;
        ItemStack icon = null;
        if (hit != null) {
            game = hit.label == null ? "" : hit.label;
            icon = iconFor(hit.icon);
        } else if (cfg.showUnmatched) {
            game = cfg.unmatchedLabel == null ? "" : cfg.unmatchedLabel;
        } else {
            return;
        }

        shownLine = cfg.messageTemplate
                .replace("{name}", name)
                .replace("{amount}", amountText)
                .replace("{game}", game);
        shownGame = game;
        shownIcon = icon;
        ticksLeft = (int) Math.max(20, Math.round(cfg.seconds * 20));
    }

    static double toMillions(BigDecimal number, String suffix) {
        double v = number.doubleValue();
        return switch (suffix) {
            case "k" -> v / 1_000.0;
            case "m" -> v;
            case "b" -> v * 1_000.0;
            case "t" -> v * 1_000_000.0;
            default -> v / 1_000_000.0; // plain number = exact money
        };
    }

    /** Digits after the point with trailing zeros removed. "5.10" -> "1", "25" -> "". */
    static String fractionOf(String numText) {
        int dot = numText.indexOf('.');
        if (dot < 0) return "";
        String f = numText.substring(dot + 1);
        int end = f.length();
        while (end > 0 && f.charAt(end - 1) == '0') end--;
        return f.substring(0, end);
    }

    static boolean matches(Rule r, double millions, String fraction) {
        if (millions < r.min - 1e-9 || millions > r.max + 1e-9) return false;
        String d = r.decimals == null ? "any" : r.decimals.trim().toLowerCase();
        if (d.isEmpty() || d.equals("any")) return true;
        if (d.equals("none") || d.equals("whole")) return fraction.isEmpty();
        return fraction.equals(d);
    }

    static ItemStack iconFor(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            Identifier ident = Identifier.tryParse(id.trim());
            if (ident == null) return null;
            ItemStack stack = new ItemStack(Registries.ITEM.get(ident));
            return stack.isEmpty() ? null : stack;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ render

    private void renderHud(DrawContext ctx, Object tickCounter) {
        if (ticksLeft <= 0 || shownLine.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.textRenderer == null || mc.getWindow() == null) return;

        float s = (float) Math.max(0.5, Math.min(3.0, cfg.hudScale));
        float cx = mc.getWindow().getScaledWidth() / 2f;

        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, (float) cfg.hudY);
        m.scale(s, s);

        int w = mc.textRenderer.getWidth(shownLine);
        ctx.drawTextWithShadow(mc.textRenderer, shownLine, -w / 2, 0, 0xFFFFFFFF);

        int y = 12;
        if (!shownGame.isEmpty()) {
            m.pushMatrix();
            m.translate(0f, (float) y);
            m.scale(1.6f, 1.6f);
            int gw = mc.textRenderer.getWidth(shownGame);
            ctx.drawTextWithShadow(mc.textRenderer, shownGame, -gw / 2, 0, 0xFF55FF55);
            m.popMatrix();
            y += 18;
        }
        if (shownIcon != null) {
            ctx.drawItem(shownIcon, -8, y);
        }
        m.popMatrix();
    }
}
