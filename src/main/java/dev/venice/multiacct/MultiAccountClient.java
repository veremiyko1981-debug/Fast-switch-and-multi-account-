package dev.venice.multiacct;

import com.google.gson.*;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.main.Main;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class MultiAccountClient implements ClientModInitializer {

    public static boolean pendingScreen = false;

    @Override
    public void onInitializeClient() {
        KeyConfig.load();
        AccountStore.load();

        MinecraftClient mc = MinecraftClient.getInstance();
        PidRegistry.register(mc.getSession().getUsername(), ProcessHandle.current().pid());
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> PidRegistry.unregister(mc.getSession().getUsername())));

        if (System.getProperty("venice.multiacct.pending") != null) pendingScreen = true;
    }

    public static void launchNext() {
        var accs = AccountStore.accounts();
        if (accs.isEmpty()) {
            MinecraftClient.getInstance().inGameHud.getChatHud()
                    .addMessage(Text.literal("[MA] аккаунтов нет, нажми F10"));
            return;
        }
        String cur = MinecraftClient.getInstance().getSession().getUsername();
        int idx = 0;
        for (int i = 0; i < accs.size(); i++) {
            if (accs.get(i).name().equalsIgnoreCase(cur)) { idx = (i + 1) % accs.size(); break; }
        }
        Relauncher.launchOrFocus(accs.get(idx));
    }

    // ================= аккаунты =================

    record OfflineAccount(String name, UUID uuid) {
        static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

        static OfflineAccount of(String name) {
            return new OfflineAccount(name,
                    UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
        }
    }

    static final class AccountStore {
        private static final Path FILE =
                FabricLoader.getInstance().getConfigDir().resolve("multiaccount/accounts.json");
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final List<OfflineAccount> ACCOUNTS = new ArrayList<>();

        static List<OfflineAccount> accounts() { return ACCOUNTS; }

        static void load() {
            ACCOUNTS.clear();
            if (!Files.exists(FILE)) return;
            try {
                for (JsonElement e : JsonParser.parseString(Files.readString(FILE)).getAsJsonArray()) {
                    String n = e.getAsJsonObject().get("name").getAsString();
                    if (OfflineAccount.VALID.matcher(n).matches()) ACCOUNTS.add(OfflineAccount.of(n));
                }
            } catch (Exception ex) {
                System.err.println("[multiacct] accounts.json: " + ex);
            }
        }

        static void save() {
            JsonArray arr = new JsonArray();
            for (OfflineAccount a : ACCOUNTS) {
                JsonObject o = new JsonObject();
                o.addProperty("name", a.name());
                arr.add(o);
            }
            try {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, GSON.toJson(arr));
            } catch (Exception ex) {
                System.err.println("[multiacct] save: " + ex);
            }
        }

        static boolean add(String name) {
            if (!OfflineAccount.VALID.matcher(name).matches()) return false;
            if (ACCOUNTS.stream().anyMatch(a -> a.name().equalsIgnoreCase(name))) return false;
            ACCOUNTS.add(OfflineAccount.of(name));
            save();
            return true;
        }

        static void remove(String name) {
            ACCOUNTS.removeIf(a -> a.name().equalsIgnoreCase(name));
            save();
        }
    }

    // ================= аргументы запуска =================

    static final class LaunchArgs {
        private static String[] ARGS = new String[0];
        static void set(String[] a) { ARGS = a.clone(); }
        static String[] get() { return ARGS; }
    }

    // ================= перезапуск =================

    static final class Relauncher {
        private static final String MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";
        private static final String PREFIX = "venice.multiacct.";
        private static long lastLaunch = 0;

        static void launchOrFocus(OfflineAccount acc) {
            Long existing = PidRegistry.alive().get(acc.name());
            if (existing != null) { WindowSwitcher.focus(existing); return; }
            if (System.currentTimeMillis() - lastLaunch < 1200) return;
            lastLaunch = System.currentTimeMillis();
            try {
                start(acc);
            } catch (Exception ex) {
                ex.printStackTrace();
                msg("Не смог запустить " + acc.name() + ": " + ex.getMessage());
            }
        }

        private static void start(OfflineAccount acc) throws Exception {
            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe());
            for (String jvm : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (jvm.startsWith("-D" + PREFIX)) continue;
                cmd.add(jvm);
            }
            cmd.add("-D" + PREFIX + "pending=" + acc.name());
            cmd.add("-cp");
            cmd.add(System.getProperty("java.class.path"));
            cmd.add(MAIN_CLASS);
            cmd.addAll(programArgs(acc));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(MinecraftClient.getInstance().runDirectory);
            pb.redirectErrorStream(true);

            File logDir = FabricLoader.getInstance().getConfigDir()
                    .resolve("multiaccount/logs").toFile();
            logDir.mkdirs();
            pb.redirectOutput(new File(logDir, acc.name() + "-" + System.currentTimeMillis() + ".log"));

            PidRegistry.register(acc.name(), pb.start().pid());
            msg("Запущен " + acc.name());
        }

        private static String javaExe() {
            String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                    ? "javaw.exe" : "java";
            return System.getProperty("java.home") + File.separator + "bin" + File.separator + exe;
        }

        private static List<String> programArgs(OfflineAccount acc) {
            String[] src = LaunchArgs.get();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < src.length; i++) {
                String a = src[i];
                if (a.equals("--username") || a.equals("--uuid") || a.equals("--accessToken")
                        || a.equals("--userType") || a.equals("--clientId")) { i++; continue; }
                out.add(a);
            }
            out.add("--username");    out.add(acc.name());
            out.add("--uuid");        out.add(acc.uuid().toString());
            out.add("--accessToken"); out.add("0");
            out.add("--userType");    out.add("legacy");
            return out;
        }

        private static void msg(String s) {
            MinecraftClient.getInstance().inGameHud.getChatHud()
                    .addMessage(Text.literal("[MA] " + s));
            System.out.println("[multiacct] " + s);
        }
    }

    // ================= реестр pid =================

    static final class PidRegistry {
        private static final Path DIR =
                FabricLoader.getInstance().getConfigDir().resolve("multiaccount/pids");

        static void register(String name, long pid) {
            try {
                Files.createDirectories(DIR);
                Files.writeString(DIR.resolve(name + ".pid"), Long.toString(pid));
            } catch (Exception ignored) {}
        }

        static void unregister(String name) {
            try { Files.deleteIfExists(DIR.resolve(name + ".pid")); } catch (Exception ignored) {}
        }

        static Map<String, Long> alive() {
            Map<String, Long> out = new TreeMap<>();
            if (!Files.isDirectory(DIR)) return out;
            try (var stream = Files.list(DIR)) {
                for (Path p : stream.toList()) {
                    try {
                        long pid = Long.parseLong(Files.readString(p).trim());
                        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                            out.put(p.getFileName().toString().replace(".pid", ""), pid);
                        } else {
                            Files.deleteIfExists(p);
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            return out;
        }
    }

    // ================= окна =================

    static final class WindowSwitcher {
        interface Win extends User32 {
            Win INSTANCE = Native.load("user32", Win.class);
            boolean SwitchToThisWindow(Pointer hwnd, boolean altTab);
            boolean AllowSetForegroundWindow(int pid);
        }

        private static long findWindow(long pid) {
            final long[] found = {0};
            WinUser.WNDENUMPROC cb = (hwnd, data) -> {
                IntByReference p = new IntByReference();
                Win.INSTANCE.GetWindowThreadProcessId(hwnd, p);
                if (p.getValue() == (int) pid
                        && Win.INSTANCE.IsWindowVisible(hwnd)
                        && Win.INSTANCE.GetWindowTextLength(hwnd) > 0) {
                    found[0] = Pointer.nativeValue(hwnd.getPointer());
                }
                return true;
            };
            Win.INSTANCE.EnumWindows(cb, null);
            return found[0];
        }

        static void focus(long pid) {
            if (!Platform.isWindows()) return;
            long h = findWindow(pid);
            if (h == 0) return;
            WinUser.HWND hwnd = new WinUser.HWND(Pointer.createConstant(h));
            Win.INSTANCE.AllowSetForegroundWindow((int) ProcessHandle.current().pid());
            Win.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE);
            Win.INSTANCE.SetForegroundWindow(hwnd);
            Win.INSTANCE.SwitchToThisWindow(hwnd, true);
        }

        static void cycle() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!Platform.isWindows()) {
                mc.inGameHud.getChatHud().addMessage(Text.literal("[MA] свитч только на Windows"));
                return;
            }
            Map<String, Long> alive = PidRegistry.alive();
            long self = ProcessHandle.current().pid();
            alive.put(mc.getSession().getUsername(), self);

            List<Map.Entry<String, Long>> list = new ArrayList<>(alive.entrySet());
            list.sort(Map.Entry.comparingByKey());
            int idx = 0;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getValue() == self) { idx = i; break; }
            }
            focus(list.get(Math.floorMod(idx + 1, list.size())).getValue());
        }
    }

    // ================= клавиши =================

    static final class KeyConfig {
        private static final Path FILE =
                FabricLoader.getInstance().getConfigDir().resolve("multiacct.json");
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        static int next  = GLFW.GLFW_KEY_F9;
        static int gui   = GLFW.GLFW_KEY_F10;
        static int cycle = GLFW.GLFW_KEY_F8;

        static void load() {
            try {
                if (!Files.exists(FILE)) { save(); return; }
                JsonObject o = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                next  = read(o, "next",  next);
                gui   = read(o, "gui",   gui);
                cycle = read(o, "cycle", cycle);
            } catch (Exception ex) {
                System.err.println("[multiacct] multiacct.json: " + ex);
            }
        }

        private static int read(JsonObject o, String k, int def) {
            if (!o.has(k)) return def;
            JsonElement e = o.get(k);
            if (e.getAsJsonPrimitive().isNumber()) return e.getAsInt();
            try {
                return InputUtil.fromTranslationKey("key.keyboard." + e.getAsString().toLowerCase())
                        .getCode();
            } catch (Exception ex) {
                return def;
            }
        }

        static void save() {
            JsonObject o = new JsonObject();
            o.addProperty("next",  name(next));
            o.addProperty("gui",   name(gui));
            o.addProperty("cycle", name(cycle));
            try {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, GSON.toJson(o));
            } catch (Exception ignored) {}
        }

        private static String name(int code) {
            return InputUtil.fromKeyCode(code, 0).getTranslationKey().replace("key.keyboard.", "");
        }
    }

    // ================= GUI =================

    static class MultiAccountScreen extends Screen {
        private static final Text TITLE = Text.literal("Аккаунты");
        private final Screen parent;
        private final List<ButtonWidget> rows = new ArrayList<>();
        private TextFieldWidget field;
        private int scroll = 0;

        MultiAccountScreen(Screen parent) {
            super(TITLE);
            this.parent = parent;
        }

        @Override
        protected void init() {
            rows.clear();
            addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close())
                    .dimensions(width / 2 - 60, height - 28, 120, 20).build());

            field = new TextFieldWidget(textRenderer, width / 2 - 100, height - 54, 140, 20,
                    Text.literal("ник"));
            field.setMaxLength(16);
            addDrawableChild(field);
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                if (AccountStore.add(field.getText().trim())) { field.setText(""); rebuild(); }
            }).dimensions(width / 2 + 46, height - 54, 54, 20).build());

            rebuild();
        }

        private void rebuild() {
            rows.forEach(this::remove);
            rows.clear();
            List<OfflineAccount> accs = AccountStore.accounts();
            int y = 40;
            for (int i = scroll; i < accs.size() && y < height - 70; i++) {
                OfflineAccount acc = accs.get(i);
                String label = acc.name().equalsIgnoreCase(client.getSession().getUsername())
                        ? "▶ " + acc.name() : acc.name();

                ButtonWidget row = ButtonWidget
                        .builder(Text.literal(label), b -> Relauncher.launchOrFocus(acc))
                        .dimensions(width / 2 - 110, y, 160, 20).build();
                ButtonWidget del = ButtonWidget.builder(Text.literal("×"), b -> {
                    AccountStore.remove(acc.name());
                    rebuild();
                }).dimensions(width / 2 + 54, y, 20, 20).build();

                addDrawableChild(row);
                addDrawableChild(del);
                rows.add(row);
                rows.add(del);
                y += 24;
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double h, double v) {
            if (v > 0) scroll = Math.max(0, scroll - 1);
            else scroll = Math.min(Math.max(0, AccountStore.accounts().size() - 1), scroll + 1);
            rebuild();
            return true;
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            super.render(ctx, mx, my, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, TITLE, width / 2, 16, 0xFFFFFF);
            if (AccountStore.accounts().isEmpty()) {
                ctx.drawCenteredTextWithShadow(textRenderer,
                        Text.literal("пусто — вбей ник снизу"), width / 2, 60, 0xAAAAAA);
            }
        }

        @Override
        public void close() { client.setScreen(parent); }
    }

    // ================= миксины =================

    @Mixin(Main.class)
    public static class MainMixin {
        @Inject(method = "main", at = @At("HEAD"))
        private static void multiacct$capture(String[] args, CallbackInfo ci) {
            LaunchArgs.set(args);
        }
    }

    @Mixin(Keyboard.class)
    public static class KeyboardMixin {
        @Inject(method = "onKey", at = @At("HEAD"))
        private void multiacct$onKey(long window, int key, int scancode, int action, int mods,
                                     CallbackInfo ci) {
            if (action != GLFW.GLFW_PRESS) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen != null) return;

            if (key == KeyConfig.next)       launchNext();
            else if (key == KeyConfig.gui)   mc.setScreen(new MultiAccountScreen(null));
            else if (key == KeyConfig.cycle) WindowSwitcher.cycle();
        }
    }

    @Mixin(MinecraftClient.class)
    public static class MinecraftClientMixin {
        @Inject(method = "tick", at = @At("TAIL"))
        private void multiacct$tick(CallbackInfo ci) {
            MinecraftClient self = (MinecraftClient) (Object) this;
            if (pendingScreen && self.currentScreen instanceof TitleScreen) {
                pendingScreen = false;
                self.setScreen(new MultiplayerScreen(null));
            }
        }
    }
      }
