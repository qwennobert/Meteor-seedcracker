package com.qwennobert.seedminer.hud;

import com.qwennobert.seedminer.SeedMinerAddon;
import com.qwennobert.seedminer.modules.SeedMiner;
import com.qwennobert.seedminer.seed.ResolvedSeed;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

public class SeedMinerHud extends HudElement {
    public static final HudElementInfo<SeedMinerHud> INFO = new HudElementInfo<>(SeedMinerAddon.HUD_GROUP, "seed-miner", "Shows the Seed Miner status.", SeedMinerHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showSeed = sgGeneral.add(new BoolSetting.Builder()
        .name("show-seed")
        .description("Show the seed that is used.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Text shadow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> titleColor = sgGeneral.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Color of the title.")
        .defaultValue(new SettingColor(0, 200, 255))
        .build()
    );

    private final Setting<SettingColor> textColor = sgGeneral.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    public SeedMinerHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        SeedMiner module = Modules.get().get(SeedMiner.class);

        String title = "Seed Miner";
        List<String> lines = new ArrayList<>();
        lines.add(module.status());
        lines.add("Ores: " + module.predicted() + "  Mined: " + module.mined() + "  Skipped: " + module.skipped());
        if (showSeed.get()) {
            ResolvedSeed seed = module.seed();
            lines.add("Seed: " + (seed == null ? "none" : seed.seed()));
        }

        double lineHeight = renderer.textHeight(shadow.get());
        double width = renderer.textWidth(title, shadow.get());
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, shadow.get()));
        setSize(width, lineHeight * (lines.size() + 1));

        double y = this.y;
        renderer.text(title, x, y, titleColor.get(), shadow.get());
        for (String line : lines) {
            y += lineHeight;
            renderer.text(line, x, y, textColor.get(), shadow.get());
        }
    }
}
