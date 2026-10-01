package com.limer.createtree.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import com.limer.createtree.common.TreeLayout;
import com.limer.createtree.config.Category;
import com.limer.createtree.config.SkillEntry;
import com.limer.createtree.net.ClientData;
import com.limer.createtree.net.IgnitePayload;
import com.limer.createtree.net.ModNetwork;
import com.limer.createtree.net.UnlockRequestPayload;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import java.util.Arrays;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;


public class SkillTreeScreen extends Screen {

	private static final int NODE_R = 17;
	private static final int ICON = 16;
	private static final int STAR_COUNT = 150;

	private static final int SKY_TOP = 0xFF0B1026;
	private static final int SKY_BOT = 0xFF05070F;
	private static final int STAR = 0xFFDCE6FF;
	private static final int LINK_OPEN = 0xFFE7C36A;
	private static final int LINK_LOCK = 0x70404A66;
	private static final int MEDALLION = 0xFF141A2E;
	private static final int RIM_UNLOCKED = 0xFFE7C36A;
	private static final int RIM_WAIT = 0xFF4A5066;
	private static final int RIM_POOR = 0xFF8A4A4A;


	// PERF: фиксированная таблица sin/cos вместо неограниченных кэшей по радиусу
	private static final int CIRCLE_N = 512;
	private static final float[] CIRCLE_COS = new float[CIRCLE_N];
	private static final float[] CIRCLE_SIN = new float[CIRCLE_N];
	static {
		for (int i = 0; i < CIRCLE_N; i++) {
			double a = 2 * Math.PI * i / CIRCLE_N;
			CIRCLE_COS[i] = (float) Math.cos(a);
			CIRCLE_SIN[i] = (float) Math.sin(a);
		}
	}

	// PERF: очередь иконок - все предметы рисуются одним батчем, а не flush на каждый узел
	private ItemStack[] iconStack = new ItemStack[128];
	private BakedModel[] iconModel = new BakedModel[128];
	private float[] iconX = new float[128];
	private float[] iconY = new float[128];
	private float[] iconS = new float[128];
	private int iconN = 0;

	// PERF: кэш HUD, чтобы не создавать Component/ItemStack каждый кадр
	private int hudPoints = Integer.MIN_VALUE;
	private FormattedCharSequence hudPointsSeq;
	private int hudExp = Integer.MIN_VALUE, hudPer = Integer.MIN_VALUE;
	private FormattedCharSequence hudExpSeq;
	private ResourceLocation hudContractItem;
	private ItemStack hudContractStack = ItemStack.EMPTY;
	private int hudCP = -1, hudCT = -1, hudCR = -1;
	private FormattedCharSequence hudContractSeq;
	private int hintForWidth = -1;
	private FormattedCharSequence hintSeq;
	private boolean hintWrap;
	private String hintL1, hintL2;

	private final List<Node> nodes = new ArrayList<>();
	private final List<float[]> stars = new ArrayList<>();
	private final List<Spark> sparks = new ArrayList<>();

	private final ItemStack rootIcon = new ItemStack(com.simibubi.create.AllItems.WRENCH.get());

	private final ItemStack aeroRootIcon = makeAeroIcon();

	private static ItemStack makeAeroIcon() {
		net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(
			ResourceLocation.fromNamespaceAndPath("aeronautics", "aviators_goggles"));
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}


	private TreeLayout.Layout layout = new TreeLayout.Layout();
	private float[] worldX = new float[1];
	private float[] worldY = new float[1];
	private int[][] parents = new int[0][];
	
	private float[] planetCX = new float[0];
	private float[] planetCY = new float[0];
	private Component[] planetNames = new Component[0];


	private float[] scrX = new float[1];
	private float[] scrY = new float[1];
	private boolean[] stUnlocked = new boolean[0];
	
	private boolean[] branchComplete = new boolean[0];
	private boolean[] stParentsOk = new boolean[0];
	private boolean[] stAffordable = new boolean[0];

	private float panX, panY;
	private float zoom = 1f;
	private boolean centered = false;
	private double dragStartX, dragStartY, dragPanX, dragPanY, moved;
	private boolean dragging = false;
	private Set<ResourceLocation> lastUnlocked = null;


	private int focusK = -2;
	private long focusStartMs = 0;
	private float focusFromX, focusFromY, focusFromZoom;
	private float focusZoom = 1f;
	private float focusOffX = 0, focusOffY = 0;
	private static final float FOCUS_MAX_OFF = 160f;
	private static final long FOCUS_MS = 900;
	private static final float PLANET_FOCUS_ZOOM = 1.5f;
	private static final float SUN_FOCUS_ZOOM = 1.0f;
	private static final float FOCUS_MIN_ZOOM = 0.65f;

	
	private float focusMaxOff() {
		if (layout == null)
			return FOCUS_MAX_OFF;
		float margin = 0.5f;
		if (focusK == -1 && layout.mainRings > 0)
			return (layout.mainRings + margin) * TreeLayout.RING_STEP * zoom;
		if (focusK == -3 && layout.aeroRings > 0)
			return (layout.aeroRings + margin) * TreeLayout.RING_STEP * zoom;
		if (focusK >= 0 && focusK < layout.planetRings.length && layout.planetRings[focusK] > 0)

			return Math.max(80f, (layout.planetRings[focusK] + 0.3f) * TreeLayout.PLANET_STEP * zoom);
		return FOCUS_MAX_OFF;
	}


	private static final long OPEN_MS = 450;
	private static final long CLOSE_MS = 260;
	private boolean opening = true;
	private long openStartMs = -1;
	private boolean closing = false;
	private long closeStartMs = 0;

	public SkillTreeScreen() {
		super(Component.translatable("screen.createtree.title"));
	}

	@Override
	protected void init() {
		super.init();
		rebuild();
		if (stars.isEmpty())
			seedStars();
	}

	private void rebuild() {
		nodes.clear();

		List<SkillEntry> ordered = List.copyOf(ClientData.tree().values());
		for (SkillEntry e : ordered) {
			net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(e.item());
			nodes.add(new Node(e, item == null ? ItemStack.EMPTY : new ItemStack(item)));
		}
		int total = nodes.size();

		layout = TreeLayout.compute(ordered);
		planetCX = new float[layout.planetIds.length];
		planetCY = new float[layout.planetIds.length];
		planetNames = new Component[layout.planetIds.length];
		for (int k = 0; k < planetNames.length; k++)
			planetNames[k] = Component.translatable("branch.createtree." + layout.planetIds[k]);


		int slots = total + 2;
		worldX = new float[slots];
		worldY = new float[slots];
		scrX = new float[slots];
		scrY = new float[slots];


		parents = new int[total][];
		for (int i = 0; i < total; i++) {
			int[] src = layout.parents[i];
			parents[i] = new int[src == null ? 0 : src.length];
			for (int k = 0; k < parents[i].length; k++)
				parents[i][k] = src[k] == -2 ? total + 1 : src[k] + 1;
		}

		stUnlocked = new boolean[total];
		stParentsOk = new boolean[total];
		stAffordable = new boolean[total];
		branchComplete = new boolean[layout.planetIds.length];


		planetNodes = new int[layout.planetIds.length][];
		for (int k = 0; k < planetNodes.length; k++) {
			List<Integer> list = new ArrayList<>();
			for (int i = 0; i < total; i++)
				if (layout.entryPlanet[i] == k)
					list.add(i);
			planetNodes[k] = list.stream().mapToInt(Integer::intValue).toArray();
		}
	}

	
	private int[][] planetNodes = new int[0][];

	
	private int aeroSlot() {
		return nodes.size() + 1;
	}

	
	private int maxSlot() {
		return nodes.size() + (layout.aeroPresent ? 1 : 0);
	}

	private void seedStars() {

		Random rnd = new Random(20260908L);
		for (int i = 0; i < STAR_COUNT; i++)
			stars.add(new float[] { rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 1.4f, rnd.nextFloat() });
	}


	private float sx(float wxv) {
		return wxv * zoom + panX;
	}

	private float sy(float wyv) {
		return wyv * zoom + panY;
	}

	private void centerView() {
		panX = width / 2f;
		panY = height / 2f;
	}


	@Override
	public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
	}

	@Override
	protected void renderBlurredBackground(float partialTick) {
	}

	@Override
	public void renderTransparentBackground(GuiGraphics gui) {
	}


	@Override
	public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		if (openStartMs < 0)
			openStartMs = now;


		if (phase == -1) {
			phase = ClientData.sunIgnited() ? 2 : 0;
			if (phase == 0) {
				zoom = INTRO_ZOOM;
				centerView();
			}
		}
		if (phase == 0 && ClientData.sunIgnited()) {

			phase = 1;
			birthStartMs = now;
		}


		float fade;
		if (closing) {
			fade = 1f - Mth.clamp((now - closeStartMs) / (float) CLOSE_MS, 0f, 1f);
		} else {
			float t = Mth.clamp((now - openStartMs) / (float) OPEN_MS, 0f, 1f);
			fade = 1 - (1 - t) * (1 - t);
		}

		gui.fillGradient(0, 0, width, height, SKY_TOP, SKY_BOT);
		if (!centered) {
			centerView();
			centered = true;
		}


		if (phase == 0) {
			drawStars(gui, now);
			drawWrenchIntro(gui, mouseX, mouseY, now);

			super.render(gui, mouseX, mouseY, partialTick);
			if (fade < 1f)
				gui.fill(0, 0, width, height, ((int) ((1f - fade) * 255) << 24) | 0x030509);
			if (closing && now - closeStartMs >= CLOSE_MS) {
				closing = false;
				super.onClose();
			}
			return;
		}


		if (phase == 1) {
			float t = Mth.clamp((now - birthStartMs) / (float) BIRTH_MS, 0f, 1f);

			hudAlpha = Mth.clamp((t - 0.5f) / 0.5f, 0f, 1f);
			float ease = 1 - (1 - t) * (1 - t) * (1 - t);
			sunGrow = Mth.clamp(t / 0.45f, 0f, 1f);
			planetFly = Mth.clamp((t - 0.25f) / 0.45f, 0f, 1f);
			nodeGrow = Mth.clamp((t - 0.55f) / 0.45f, 0f, 1f);
			zoom = INTRO_ZOOM + (1f - INTRO_ZOOM) * ease;
			panX = width / 2f;
			panY = height / 2f;
			if (t >= 1f) {
				phase = 2;
				sunGrow = 1f;
				planetFly = 1f;
				nodeGrow = 1f;
				zoom = 1f;
				setFocus(-1);
				if (hintShownSince < 0)
					hintShownSince = Util.getMillis();
			}
		} else {
			sunGrow = 1f;
			planetFly = 1f;
			nodeGrow = 1f;

			if (phase == 2) {
				hudAlpha = Math.min(1f, hudAlpha + partialTick / 6f);
				if (hintShownSince < 0)
					hintShownSince = Util.getMillis();
			}
		}

		updateFrameState();
		applyFocus(now);
		boolean focused = focusK >= -3 && focusK != -2;

		int hoverBody = (!focused && phase == 2) ? bodyAt(mouseX, mouseY) : -2;

		drawStars(gui, now);


		drawOrbitTracks(gui);
		drawSun(gui, now);
		if (hoverBody == -1)
			drawSunHover(gui);
		if (layout.aeroPresent) {
			drawAeroSun(gui, now);
			if (hoverBody == -3)
				drawAeroSunHover(gui);
		}
		drawPlanetsOnly(gui, hoverBody);

		drawConstellations(gui, now);

		int hovered = -2;
		if (focused) {

			if (phase == 2 || planetFly > 0)
				drawOrbits(gui);
			if (nodeGrow > 0) {
				drawLinks(gui, focusK);
				for (int slot = 0; slot <= maxSlot(); slot++) {
					if (!slotInFocus(slot))
						continue;
					boolean hov = overSlot(mouseX, mouseY, slot);
					drawMedallion(gui, slot, hov && phase == 2, now);
					if (hov)
						hovered = slot == aeroSlot() ? -3 : slot - 1;
				}

				flushIcons(gui);

				if (phase == 2)
					drawBadges(gui);
			}

			if (phase == 1 && sunGrow < 1f)
				drawWrenchShrink(gui, now);
			drawSparks(gui, now);
		} else if (phase == 1) {

			if (nodeGrow > 0) {
				drawLinks(gui, -1);
				for (int slot = 0; slot <= maxSlot(); slot++) {
					boolean hov = false;
					drawMedallion(gui, slot, hov, now);
				}
				flushIcons(gui);
			}
			if (phase == 1 && sunGrow < 1f)
				drawWrenchShrink(gui, now);
		}


		drawPlanetLabels(gui, hoverBody);


		if (hudAlpha > 0.001f)
			drawHud(gui, hudAlpha);

		super.render(gui, mouseX, mouseY, partialTick);


		if (focusK >= -1 || focusK == -3) {
			if (phase == 2 && fade >= 0.999f && !closing) {
				if (hovered == -1)
					gui.renderTooltip(font, Component.translatable("screen.createtree.root"), mouseX, mouseY);
				else if (hovered == -3)
					gui.renderTooltip(font, Component.translatable("screen.createtree.aero_root"), mouseX, mouseY);
				else if (hovered >= 0)
					renderNodeTooltip(gui, nodes.get(hovered), hovered, mouseX, mouseY);
			}
		}


		if (fade < 1f) {
			int a = (int) ((1f - fade) * 255) << 24;
			gui.fill(0, 0, width, height, a | 0x030509);
		}

		if (closing && now - closeStartMs >= CLOSE_MS) {
			closing = false;
			super.onClose();
			return;
		}

		detectUnlocks();
	}

	
	private void drawWrenchIntro(GuiGraphics gui, int mouseX, int mouseY, long now) {
		int cx = width / 2;
		int cy = height / 2;
		float bob = 6f * Mth.sin(now / 600f);
		float pulse = 0.5f + 0.5f * Mth.sin(now / 500f);


		drawGlow(gui, cx, (int) (cy + bob), 54 + (int) (6 * pulse));


		PoseStack pose = gui.pose();
		pose.pushPose();
		pose.translate(cx - 32, cy + bob - 32, 150);
		pose.scale(4f, 4f, 1f);
		gui.renderItem(rootIcon, 0, 0);
		pose.popPose();

		boolean hov = Math.hypot(mouseX - cx, mouseY - (cy + bob)) <= WRENCH_CLICK_R;
		if (hov) {

			drawSolidCircle(gui, cx, (int) (cy + bob), 50, 0xC0FFE9A0);
			gui.renderTooltip(font, Component.translatable("screen.createtree.click_wrench"), mouseX, mouseY);
		}

		gui.drawCenteredString(font, Component.translatable("screen.createtree.intro"), cx, cy + 96, 0xC8CCDD);
	}

	private void drawGlow(GuiGraphics gui, int cx, int cy, int r) {

		fillDisc(gui, cx, cy, r, 0x0CFFB040);
		fillDisc(gui, cx, cy, (int) (r * 0.72f), 0x10FFC050);
		fillDisc(gui, cx, cy, (int) (r * 0.48f), 0x14FFD070);
	}

	
	private void drawWrenchShrink(GuiGraphics gui, long now) {
		float t = 1f - sunGrow;
		if (t <= 0.02f)
			return;
		int cx = Math.round(scrX[0]);
		int cy = Math.round(scrY[0]);
		float scale = 1f + 3f * t;
		PoseStack pose = gui.pose();
		pose.pushPose();
		pose.translate(cx - 8 * scale, cy - 8 * scale, 160);
		pose.scale(scale, scale, 1);
		gui.renderItem(rootIcon, 0, 0);
		pose.popPose();
	}

	
	private void updateFrameState() {
		int total = nodes.size();
		long now = Util.getMillis();


		float fly = phase == 1 ? planetFly : 1f;

		for (int k = 0; k < layout.planetIds.length; k++) {
			planetCX[k] = TreeLayout.planetX(layout, k, now) * fly;
			planetCY[k] = TreeLayout.planetY(layout, k, now) * fly;
		}
		worldX[0] = 0;
		worldY[0] = 0;
		for (int i = 0; i < total; i++) {
			int k = layout.entryPlanet[i];
			if (k == -2) {

				worldX[i + 1] = layout.aeroCX * fly + layout.relX[i] * fly;
				worldY[i + 1] = layout.aeroCY + layout.relY[i] * fly;
			} else if (k < 0) {
				worldX[i + 1] = layout.relX[i];
				worldY[i + 1] = layout.relY[i];
			} else {
				worldX[i + 1] = planetCX[k] + layout.relX[i] * fly;
				worldY[i + 1] = planetCY[k] + layout.relY[i] * fly;
			}
		}

		if (layout.aeroPresent && worldX.length > total + 1) {
			worldX[total + 1] = layout.aeroCX * fly;
			worldY[total + 1] = layout.aeroCY;
		}

		int points = ClientData.points();
		for (int i = 0; i < total; i++) {
			stUnlocked[i] = ClientData.isUnlocked(nodes.get(i).entry.item());
			stAffordable[i] = points >= effectiveCost(nodes.get(i).entry);
			boolean ok = false;
			for (int p : parents[i])
				if (p == 0 || p == aeroSlot() || (p >= 1 && p <= total && stUnlocked[p - 1])) {
					ok = true;
					break;
				}
			stParentsOk[i] = ok;
		}


		for (int k = 0; k < planetNodes.length; k++) {
			boolean all = planetNodes[k].length > 0;
			for (int idx : planetNodes[k])
				if (!stUnlocked[idx]) {
					all = false;
					break;
				}
			branchComplete[k] = all;
		}
	}

	
	private void applyFocus(long now) {
		if (focusK >= -3 && focusK != -2) {
			float tx = focusCenterX();
			float ty = focusCenterY();
			float t = Mth.clamp((now - focusStartMs) / (float) FOCUS_MS, 0f, 1f);
			float e = 1 - (1 - t) * (1 - t) * (1 - t);
			zoom = focusFromZoom + (focusZoom - focusFromZoom) * e;
			panX = focusFromX + (width / 2f - tx * zoom - focusFromX) * e;
			panY = focusFromY + (height / 2f - ty * zoom - focusFromY) * e;

			if (t >= 1f) {
				panX = width / 2f - tx * zoom + focusOffX;
				panY = height / 2f - ty * zoom + focusOffY;
			}
		}

		for (int slot = 0; slot < scrX.length; slot++) {
			scrX[slot] = sx(worldX[slot]);
			scrY[slot] = sy(worldY[slot]);
		}
	}

	
	private float focusCenterX() {
		return focusK == -1 ? 0 : (focusK == -3 ? layout.aeroCX : planetCX[focusK]);
	}

	private float focusCenterY() {
		return focusK == -1 ? 0 : (focusK == -3 ? layout.aeroCY : planetCY[focusK]);
	}

	private void setFocus(int k) {
		focusK = k;
		focusStartMs = Util.getMillis();
		focusFromX = panX;
		focusFromY = panY;
		focusFromZoom = zoom;
		focusZoom = (k == -1 || k == -3) ? SUN_FOCUS_ZOOM : PLANET_FOCUS_ZOOM;
		focusOffX = 0;
		focusOffY = 0;
	}

	
	private int bodyAt(double mx, double my) {
		for (int k = 0; k < layout.planetIds.length; k++) {
			float dx = (float) (mx - sx(planetCX[k]));
			float dy = (float) (my - sy(planetCY[k]));
			float r = Math.max(24 * zoom, 14);
			if (dx * dx + dy * dy <= r * r)
				return k;
		}
		float dx = (float) (mx - scrX[0]);
		float dy = (float) (my - scrY[0]);
		float r = Math.max(34 * zoom, 16);
		if (dx * dx + dy * dy <= r * r)
			return -1;
		if (layout.aeroPresent) {
			float ax = (float) (mx - scrX[aeroSlot()]);
			float ay = (float) (my - scrY[aeroSlot()]);
			float ar = Math.max(34 * zoom, 16);
			if (ax * ax + ay * ay <= ar * ar)
				return -3;
		}
		return -2;
	}

	private void detectUnlocks() {
		Set<ResourceLocation> now = ClientData.unlocked();
		if (lastUnlocked == null) {
			lastUnlocked = new HashSet<>(now);
			return;
		}
		if (now.size() == lastUnlocked.size())
			return;
		for (int i = 0; i < nodes.size(); i++) {
			ResourceLocation id = nodes.get(i).entry.item();
			if (now.contains(id) && !lastUnlocked.contains(id)) {
				playSound(SoundEvents.PLAYER_LEVELUP, 1.4f);
				burst(i);
			}
		}
		lastUnlocked = new HashSet<>(now);
	}

	private void drawStars(GuiGraphics gui, long now) {
		float starPhase = now / 700f;

		float ox = -Math.round(panX) * 0.15f;
		float oy = -Math.round(panY) * 0.15f;
		for (float[] s : stars) {
			int x = (int) Mth.positiveModulo(s[0] * width + ox, width);
			int y = (int) Mth.positiveModulo(s[1] * height + oy, height);
			float tw = 0.5f + 0.5f * Mth.sin(starPhase + s[3] * 12f);
			int size = Math.max(1, Math.round(s[2] * (0.7f + 0.3f * tw)));
			int color = ((int) (120 + 135 * tw) << 24) | (STAR & 0x00FFFFFF);
			gui.fill(x, y, x + size, y + size, color);
		}
	}

	
	private void drawOrbits(GuiGraphics gui) {
		if (nodes.isEmpty())
			return;

		if (focusK == -3) {

			int cx = Math.round(scrX[aeroSlot()]);
			int cy = Math.round(scrY[aeroSlot()]);
			for (int r = 1; r <= layout.aeroRings; r++) {
				int rad = Math.round(TreeLayout.RING_STEP * r * zoom);
				if (rad < 8)
					continue;
				drawDashedCircle(gui, cx, cy, rad, 0xFF4A3358);
			}
		} else if (focusK == -1) {

			int cx = Math.round(scrX[0]);
			int cy = Math.round(scrY[0]);
			for (int r = 1; r <= layout.mainRings; r++) {
				int rad = Math.round(TreeLayout.RING_STEP * r * zoom);
				if (rad < 8)
					continue;
				drawDashedCircle(gui, cx, cy, rad, 0xFF2A3350);
			}
		} else if (focusK >= 0) {

			int px = Math.round(sx(planetCX[focusK]));
			int py = Math.round(sy(planetCY[focusK]));
			for (int r = 1; r <= layout.planetRings[focusK]; r++) {
				int rad = Math.round(TreeLayout.PLANET_STEP * r * zoom);
				if (rad < 8)
					continue;
				drawDashedCircle(gui, px, py, rad, 0xFF3A4568);
			}
		}
	}

	
	private void drawSun(GuiGraphics gui, long now) {
		float cx = scrX[0];
		float cy = scrY[0];
		float pulse = 0.5f + 0.5f * Mth.sin(now / 900f);

		int sunR = zoom >= 0.5f ? Math.round(30 * zoom) : Math.round(7 + 14 * zoom);
		sunR = Math.max(14, sunR);

		float grow = phase == 1 ? sunGrow : 1f;
		if (grow <= 0.01f)
			return;
		sunR = Math.max(2, Math.round(sunR * grow));
		int glow = Math.max(2, Math.round(14 * zoom * grow));
		PoseStack pose = gui.pose();
		pose.pushPose();
		pose.translate(cx, cy, 0);
		fillDisc(gui, 0, 0, sunR + glow, 0x14FFB040);
		fillDisc(gui, 0, 0, sunR + Math.round(glow * 0.5f), 0x22FFC050);
		fillDisc(gui, 0, 0, sunR, lerpColor(0xFFFFA030, 0xFFFFC060, pulse));
		if (sunR >= 6)
			fillDisc(gui, -Math.round(sunR * 0.25f), -Math.round(sunR * 0.25f),
				Math.max(2, Math.round(sunR * 0.45f)), 0x50FFE0A0);
		pose.popPose();
	}

	private static final int[] PLANET_PALETTE = {
		0xFF4A90D9, 0xFFD9834A, 0xFF6AB04A, 0xFFB04AD9, 0xFFD94A6A, 0xFF4AD9C8
	};

	
	private void drawAeroSun(GuiGraphics gui, long now) {
		int slot = aeroSlot();
		if (slot >= scrX.length)
			return;
		int cx = Math.round(scrX[slot]);
		int cy = Math.round(scrY[slot]);
		float pulse = 0.5f + 0.5f * Mth.sin(now / 900f + 2f);
		int sunR = zoom >= 0.5f ? Math.round(30 * zoom) : Math.round(7 + 14 * zoom);
		sunR = Math.max(14, sunR);
		float grow = phase == 1 ? sunGrow : 1f;
		if (grow <= 0.01f)
			return;
		sunR = Math.max(2, Math.round(sunR * grow));
		int glow = Math.max(2, Math.round(14 * zoom * grow));
		fillDisc(gui, cx, cy, sunR + glow, 0x14B040FF);
		fillDisc(gui, cx, cy, sunR + Math.round(glow * 0.5f), 0x22C060FF);
		fillDisc(gui, cx, cy, sunR, lerpColor(0xFFA040F0, 0xFFC880FF, pulse));
		if (sunR >= 6)
			fillDisc(gui, cx - Math.round(sunR * 0.25f), cy - Math.round(sunR * 0.25f),
				Math.max(2, Math.round(sunR * 0.45f)), 0x50E8C0FF);

		if (zoom >= 0.5f) {
			PoseStack pose = gui.pose();
			pose.pushPose();
			pose.translate(0, 0, 200);
			gui.drawCenteredString(font, Component.translatable("branch.createtree.aeronautics"),
				cx, cy + sunR + Math.round(10 * zoom), focusK == -3 ? 0xFFE9A0 : 0xD8C0F0);
			pose.popPose();
		}
	}

	private void drawAeroSunHover(GuiGraphics gui) {
		int slot = aeroSlot();
		if (slot >= scrX.length)
			return;
		int cx = Math.round(scrX[slot]);
		int cy = Math.round(scrY[slot]);
		int sunR = Math.max(14, zoom >= 0.5f ? Math.round(30 * zoom) : Math.round(7 + 14 * zoom));
		drawSolidCircle(gui, cx, cy, sunR + 5, 0xE0FFFFFF);
	}

	
	private void drawOrbitTracks(GuiGraphics gui) {
		int cx = Math.round(scrX[0]);
		int cy = Math.round(scrY[0]);
		for (int k = 0; k < layout.planetIds.length; k++) {
			int orbitR = Math.round(layout.planetOrbitR[k] * zoom);
			if (orbitR < 4)
				continue;
			int track = focusK == k ? 0xE0FFD24E : ((PLANET_PALETTE[k % PLANET_PALETTE.length] & 0x00FFFFFF) | 0x80000000);
			drawSolidCircle(gui, cx, cy, orbitR, track);
		}
	}

	
	private void drawPlanetsOnly(GuiGraphics gui, int hoverBody) {
		for (int k = 0; k < layout.planetIds.length; k++) {
			float px = sx(planetCX[k]);
			float py = sy(planetCY[k]);
			int pr = Math.max(4, Math.round(20 * zoom));
			if (px + pr * 2 < 0 || py + pr * 2 < 0 || px - pr * 2 > width || py - pr * 2 > height)
				continue;
			int base = PLANET_PALETTE[k % PLANET_PALETTE.length];
			boolean golden = layout.planetIds[k].equals(ClientData.goldenBranch());
			PoseStack pose = gui.pose();
			pose.pushPose();
			pose.translate(px, py, 0);
			if (golden) {
				float gp = 0.5f + 0.5f * Mth.sin(Util.getMillis() / 300f);
				fillDisc(gui, 0, 0, pr + 6, 0x30FFD24E);
				drawSolidCircle(gui, 0, 0, pr + 3, (int) (0x90 + 0x50 * gp) << 24 | 0xFFD24E);
			}

			if (branchComplete.length > k && branchComplete[k]) {
				float sp = 0.5f + 0.5f * Mth.sin(Util.getMillis() / 700f + k);
				fillDisc(gui, 0, 0, pr + 8, (int) (0x14 + 0x0A * sp) << 24 | 0xFFF2C0);
			}
			fillDisc(gui, 0, 0, pr + 2, (base & 0x00FFFFFF) | 0x18000000);
			fillDisc(gui, 0, 0, pr, golden ? 0xFFFFD24E : base);
			fillDisc(gui, -pr / 3, -pr / 3, Math.max(1, pr / 2), 0x40FFFFFF);
			if (focusK == k)
				drawSolidCircle(gui, 0, 0, pr + 4, 0xC0FFE9A0);

			if (hoverBody == k)
				drawSolidCircle(gui, 0, 0, pr + 6, 0xE0FFFFFF);
			pose.popPose();
		}
	}

	
	private void drawConstellations(GuiGraphics gui, long now) {
		for (int k = 0; k < planetNodes.length; k++) {
			if (!branchComplete[k] || focusK == k)
				continue;
			float px = sx(planetCX[k]);
			float py = sy(planetCY[k]);
			if (px < -300 || py < -300 || px > width + 300 || py > height + 300)
				continue;

			float scale = Mth.clamp(zoom, 0.35f, 0.55f) * 0.5f;
			int base = PLANET_PALETTE[k % PLANET_PALETTE.length];
			int lineColor = (base & 0x00FFFFFF) | 0x50000000;


			for (int idx : planetNodes[k]) {
				float x1 = px + layout.relX[idx] * scale;
				float y1 = py + layout.relY[idx] * scale;
				for (int p : parents[idx]) {
					if (p == 0 || p > nodes.size())
						continue;
					float x0 = px + layout.relX[p - 1] * scale;
					float y0 = py + layout.relY[p - 1] * scale;
					drawStarLine(gui, x0, y0, x1, y1, lineColor);
				}
			}
			for (int j = 0; j < planetNodes[k].length; j++) {
				int idx = planetNodes[k][j];
				float x = px + layout.relX[idx] * scale;
				float y = py + layout.relY[idx] * scale;
				drawStar(gui, Math.round(x), Math.round(y), now + j * 700L, base);
			}
		}
	}

	private void drawStarLine(GuiGraphics gui, float x0, float y0, float x1, float y1, int color) {
		int steps = (int) Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		if (steps <= 0)
			return;
		for (int s = 0; s <= steps; s += 2) {
			float t = s / (float) steps;
			int x = Math.round(x0 + (x1 - x0) * t);
			int y = Math.round(y0 + (y1 - y0) * t);
			if (x < 0 || y < 0 || x >= width || y >= height)
				continue;
			gui.fill(x, y, x + 1, y + 1, color);
		}
	}

	
	private void drawStar(GuiGraphics gui, int x, int y, long phase, int base) {
		float tw = 0.6f + 0.4f * Mth.sin(phase / 500f);
		int a = (int) (200 * tw);
		int col = (a << 24) | 0xFFFFFF;
		int dim = (a / 2 << 24) | (base & 0x00FFFFFF);

		gui.fill(x - 3, y, x + 4, y + 1, dim);
		gui.fill(x, y - 3, x + 1, y + 4, dim);

		gui.fill(x - 1, y, x + 2, y + 1, col);
		gui.fill(x, y - 1, x + 1, y + 2, col);
	}

	
	private void drawPlanetLabels(GuiGraphics gui, int hoverBody) {
		for (int k = 0; k < layout.planetIds.length; k++) {
			float px = sx(planetCX[k]);
			float py = sy(planetCY[k]);
			int pr = Math.max(4, Math.round(20 * zoom));
			if (px + pr * 2 < 0 || py + pr * 2 < 0 || px - pr * 2 > width || py - pr * 2 > height)
				continue;
			PoseStack pose = gui.pose();
			pose.pushPose();
			pose.translate(px, py, 0);
			gui.drawCenteredString(font, planetNames[k], 0, pr + (hoverBody == k ? 8 : 4),
				hoverBody == k ? 0xFFFFFF : (branchComplete[k] ? 0xFFE070 : (focusK == k ? 0xFFE9A0 : 0xC8CCDD)));
			pose.popPose();
		}
	}

	
	private void drawSunHover(GuiGraphics gui) {
		int cx = Math.round(scrX[0]);
		int cy = Math.round(scrY[0]);
		int sunR = Math.max(14, zoom >= 0.5f ? Math.round(30 * zoom) : Math.round(7 + 14 * zoom));
		drawSolidCircle(gui, cx, cy, sunR + 5, 0xE0FFFFFF);
	}

	private void drawDashedCircle(GuiGraphics gui, int cx, int cy, int rad, int color) {
		drawCirclePoints(gui, cx, cy, rad, color, 4);
	}

	private void drawSolidCircle(GuiGraphics gui, int cx, int cy, int rad, int color) {
		drawCirclePoints(gui, cx, cy, rad, color, 1);
	}

	/** Окружность без аллокаций: точки берутся из статической таблицы sin/cos. */
	private void drawCirclePoints(GuiGraphics gui, int cx, int cy, int rad, int color, int stride) {
		if (rad <= 0 || cx + rad < 0 || cy + rad < 0 || cx - rad > width || cy - rad > height)
			return;
		int n = Mth.clamp((int) (Math.PI * rad), 48, CIRCLE_N);
		for (int i = 0; i < n; i += stride) {
			int t = i * CIRCLE_N / n;
			int x = cx + Math.round(CIRCLE_COS[t] * rad);
			int y = cy + Math.round(CIRCLE_SIN[t] * rad);
			if (x < 0 || y < 0 || x >= width || y >= height)
				continue;
			gui.fill(x, y, x + 1, y + 1, color);
		}
	}

	
	private boolean slotInFocus(int slot) {
		if (focusK == -2)
			return false;
		if (phase == 1)
			return true;
		if (slot == 0)
			return focusK == -1;
		if (layout.aeroPresent && slot == aeroSlot())
			return focusK == -3;
		int i = slot - 1;
		if (focusK == -1)
			return layout.entryPlanet[i] == -1;
		if (focusK == -3)
			return layout.entryPlanet[i] == -2;
		return layout.entryPlanet[i] == focusK;
	}

	private void drawLinks(GuiGraphics gui, int focus) {
		for (int i = 0; i < nodes.size(); i++) {
			if (phase == 2 && focus != -2 && !slotInFocus(i + 1))
				continue;
			int slot = i + 1;

			float x1 = scrX[slot], y1 = scrY[slot];
			for (int p : parents[i]) {
				if (p == 0 && layout.entryPlanet[i] >= 0)
					continue;
				float x0 = scrX[p], y0 = scrY[p];
				if (Math.max(x0, x1) < 0 || Math.min(x0, x1) > width || Math.max(y0, y1) < 0 || Math.min(y0, y1) > height)
					continue;
				boolean rootParent = p == 0 || (layout.aeroPresent && p == aeroSlot());
				int color = (rootParent || (p >= 1 && p <= nodes.size() && stUnlocked[p - 1])) ? LINK_OPEN : LINK_LOCK;
				drawLine(gui, x0, y0, x1, y1, color);
			}
		}
	}

	/** Линия толщиной 2 px одним квадом: без push/pop PoseStack и без Quaternionf на каждую связь. */
	private void drawLine(GuiGraphics gui, float x0, float y0, float x1, float y1, int color) {
		float dx = x1 - x0, dy = y1 - y0;
		float len = Mth.sqrt(dx * dx + dy * dy);
		if (len < 1f)
			return;
		float nx = -dy / len, ny = dx / len; // нормаль, ту же ориентацию обхода использует GuiGraphics.fill
		VertexConsumer vc = gui.bufferSource().getBuffer(RenderType.gui());
		Matrix4f m = gui.pose().last().pose();
		vc.addVertex(m, x0 - nx, y0 - ny, 0f).setColor(color);
		vc.addVertex(m, x0 + nx, y0 + ny, 0f).setColor(color);
		vc.addVertex(m, x1 + nx, y1 + ny, 0f).setColor(color);
		vc.addVertex(m, x1 - nx, y1 - ny, 0f).setColor(color);
	}


	private void drawMedallion(GuiGraphics gui, int slot, boolean hov, long now) {

		float grow = phase == 1 ? (slot == 0 ? Math.max(sunGrow, nodeGrow) : nodeGrow) : 1f;
		if (grow <= 0.02f)
			return;
		float cx = scrX[slot];
		float cy = scrY[slot];
		float rad = NODE_R * zoom * grow;
		if (cx + rad < 0 || cy + rad < 0 || cx - rad > width || cy - rad > height)
			return;

		int i = slot - 1;
		boolean isAeroRoot = layout.aeroPresent && slot == aeroSlot();
		boolean unlocked;
		boolean parentsOk;
		boolean affordable;
		ItemStack icon;
		if (i < 0 || isAeroRoot) {
			unlocked = true;
			parentsOk = true;
			affordable = true;
			icon = isAeroRoot ? aeroRootIcon : rootIcon;
		} else {
			unlocked = stUnlocked[i];
			parentsOk = stParentsOk[i];
			affordable = stAffordable[i];
			icon = nodes.get(i).icon;
		}

		int rim;
		if (unlocked)
			rim = RIM_UNLOCKED;
		else if (!parentsOk)
			rim = RIM_WAIT;
		else if (affordable)
			rim = pulse(now);
		else
			rim = RIM_POOR;

		int rr = Math.max(4, Math.round(rad));


		PoseStack nodePose = gui.pose();
		nodePose.pushPose();
		nodePose.translate(cx, cy, 0);
		fillDisc(gui, 0, 0, rr, rim);
		fillDisc(gui, 0, 0, rr - 2, MEDALLION);
		if (hov)
			fillDisc(gui, 0, 0, rr, 0x28FFFFFF);


		nodePose.popPose();

		float iconScale = Math.round(Mth.clamp(zoom, 0.75f, 2.5f) * grow * 4f) / 4f;
		queueIcon(icon, cx, cy, iconScale <= 1.01f ? 1f : iconScale);

		if (phase != 2)
			return;

	}

	
	private void drawBadges(GuiGraphics gui) {
		for (int slot = 0; slot <= maxSlot(); slot++) {
			if (!slotInFocus(slot))
				continue;
			float rad = NODE_R * zoom;
			float cx = scrX[slot];
			float cy = scrY[slot];
			if (cx + rad < 0 || cy + rad < 0 || cx - rad > width || cy - rad > height)
				continue;

			int i = slot - 1;
			boolean isAeroRoot = layout.aeroPresent && slot == aeroSlot();
			if (i < 0 || isAeroRoot)
				continue;

			int rr = Math.max(4, Math.round(rad));
			boolean unlocked = stUnlocked[i];
			boolean parentsOk = stParentsOk[i];
			boolean affordable = stAffordable[i];
			Category category = nodes.get(i).entry.category();
			int cost = effectiveCost(nodes.get(i).entry);


			PoseStack badgePose = gui.pose();
			badgePose.pushPose();
			badgePose.translate(cx, cy, 200);

			if (unlocked) {
				int gx = rr - 8, gy = rr - 8;
				gui.fill(gx, gy + 2, gx + 2, gy + 4, 0xFF38B038);
				gui.fill(gx + 2, gy + 4, gx + 4, gy + 6, 0xFF38B038);
				gui.fill(gx + 4, gy, gx + 6, gy + 6, 0xFF38B038);
			} else if (!parentsOk) {

				drawPadlock(gui, Math.round(rr * 0.7f), Math.round(rr * 0.7f));
			} else if (zoom >= 0.8f) {
				String c = String.valueOf(cost);
				int cw = font.width(c);

				int bx = Math.round(rr * 0.7f), by = Math.round(rr * 0.7f) - 4;
				gui.fill(bx - 1, by - 1, bx + cw + 1, by + 9, 0xC0000000);
				gui.drawString(font, c, bx, by, affordable ? 0xFFE070 : 0xFFB07070, true);
			}

			if (zoom >= 0.8f) {

				int dx = -Math.round(rr * 0.7f) - 3, dy = -Math.round(rr * 0.7f) - 3;
				gui.fill(dx, dy, dx + 3, dy + 3, categoryColor(category));
			}
			badgePose.popPose();
		}
	}

	private boolean overSlot(double mx, double my, int slot) {
		float dx = (float) (mx - scrX[slot]);
		float dy = (float) (my - scrY[slot]);
		float r = NODE_R * zoom + 2;
		return dx * dx + dy * dy <= r * r;
	}

	private boolean anyParentUnlocked(int i) {
		for (int p : parents[i])
			if (p == 0 || (layout.aeroPresent && p == aeroSlot())
				|| (p >= 1 && p <= nodes.size() && ClientData.isUnlocked(nodes.get(p - 1).entry.item())))
				return true;
		return false;
	}

	private static int lerpColor(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return 0xFF000000
			| ((int) (ar + (br - ar) * t) << 16)
			| ((int) (ag + (bg - ag) * t) << 8)
			| (int) (ab + (bb - ab) * t);
	}


	private void fillDisc(GuiGraphics gui, int cx, int cy, int r, int color) {
		if (r <= 0)
			return;

		if (cx + r < 0 || cy + r < 0 || cx - r > width || cy - r > height)
			return;
		int rr = r * r + r;
		for (int dy = -r; dy <= r; dy += 2) {
			int hw = (int) Math.sqrt(Math.max(0, rr - dy * dy));
			gui.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 2, color);
		}
	}

	// ---- батч иконок ----
	private void queueIcon(ItemStack stack, float x, float y, float scale) {
		if (stack == null || stack.isEmpty())
			return;
		if (iconN == iconStack.length) {
			int cap = iconN * 2;
			iconStack = Arrays.copyOf(iconStack, cap);
			iconModel = Arrays.copyOf(iconModel, cap);
			iconX = Arrays.copyOf(iconX, cap);
			iconY = Arrays.copyOf(iconY, cap);
			iconS = Arrays.copyOf(iconS, cap);
		}
		iconStack[iconN] = stack;
		iconX[iconN] = x;
		iconY[iconN] = y;
		iconS[iconN] = scale;
		iconN++;
	}

	/**
	 * Рисует все накопленные иконки за 2 прохода (блочное и плоское освещение) и 2 flush,
	 * вместо одного flush на каждый предмет, как делает GuiGraphics.renderItem.
	 */
	private void flushIcons(GuiGraphics gui) {
		if (iconN == 0)
			return;
		ItemRenderer ir = Minecraft.getInstance().getItemRenderer();
		gui.flush(); // сначала выводим всё, что нарисовано до иконок (фоны, связи)
		boolean anyFlat = false;
		for (int i = 0; i < iconN; i++) {
			iconModel[i] = ir.getModel(iconStack[i], null, null, 0);
			if (!iconModel[i].usesBlockLight())
				anyFlat = true;
		}
		drawIconPass(gui, ir, false);
		if (anyFlat) {
			Lighting.setupForFlatItems();
			drawIconPass(gui, ir, true);
			Lighting.setupFor3DItems();
		}
		for (int i = 0; i < iconN; i++) {
			iconStack[i] = null;
			iconModel[i] = null;
		}
		iconN = 0;
	}

	private void drawIconPass(GuiGraphics gui, ItemRenderer ir, boolean flat) {
		PoseStack pose = gui.pose();
		boolean any = false;
		for (int i = 0; i < iconN; i++) {
			BakedModel m = iconModel[i];
			if (m.usesBlockLight() == flat)
				continue;
			any = true;
			pose.pushPose();
			pose.translate(iconX[i], iconY[i], 150f);
			float s = 16f * iconS[i];
			pose.scale(s, -s, s);
			ir.render(iconStack[i], ItemDisplayContext.GUI, false, pose, gui.bufferSource(),
				15728880, OverlayTexture.NO_OVERLAY, m);
			pose.popPose();
		}
		if (any)
			gui.flush();
	}


	private void drawPadlock(GuiGraphics gui, int x, int y) {
		gui.fill(x + 1, y, x + 2, y + 3, 0xFFB0B0B8);
		gui.fill(x + 6, y, x + 7, y + 3, 0xFFB0B0B8);
		gui.fill(x + 2, y - 1, x + 6, y, 0xFFB0B0B8);
		gui.fill(x, y + 3, x + 8, y + 9, 0xFFD8D8E0);
		gui.fill(x + 3, y + 5, x + 5, y + 7, 0xFF404048);
	}

	private int pulse(long now) {
		float t = (now % 1200) / 1200f;
		float s = 0.5f + 0.5f * Mth.sin(t * Mth.TWO_PI);
		int r = (int) (200 + 55 * s);
		int g = (int) (160 + 60 * s);
		return 0xFF000000 | (r << 16) | (g << 8) | 0x40;
	}

	private void burst(int index) {
		if (index < 0)
			return;
		float x = worldX[index + 1], y = worldY[index + 1];
		Random rnd = new Random();
		for (int k = 0; k < 14; k++) {
			float a = rnd.nextFloat() * Mth.TWO_PI;
			float sp = 0.6f + rnd.nextFloat() * 1.8f;
			sparks.add(new Spark(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, 500 + rnd.nextInt(300)));
		}
	}

	private void drawSparks(GuiGraphics gui, long now) {
		sparks.removeIf(s -> now - s.born > s.life);
		for (Spark s : sparks) {
			float age = (now - s.born) / (float) s.life;
			int px = Math.round(sx(s.x + s.vx * age * 40));
			int py = Math.round(sy(s.y + s.vy * age * 40));
			int color = ((int) (255 * (1 - age)) << 24) | 0xFFE7A3;
			gui.fill(px, py, px + 2, py + 2, color);
		}
	}

	
	private int hudMode = 0;
	
	private float hudAlpha = 0f;
	
	private long hintShownSince = -1;
	private static final long HINT_HIDE_DELAY = 5000;
	private static final long HINT_HIDE_FADE = 800;

	private float hintAlpha() {
		if (hintShownSince < 0)
			return 1f;
		long e = Util.getMillis() - hintShownSince;
		if (e < HINT_HIDE_DELAY)
			return 1f;
		return Mth.clamp(1f - (e - HINT_HIDE_DELAY) / (float) HINT_HIDE_FADE, 0f, 1f);
	}


	private int phase = -1;
	private long birthStartMs = -1;
	private static final long BIRTH_MS = 2200;
	private static final float INTRO_ZOOM = 0.15f;
	private static final int WRENCH_CLICK_R = 48;

	private float sunGrow = 0f;
	private float planetFly = 0f;
	private float nodeGrow = 0f;


	private void drawHud(GuiGraphics gui, float alpha) {

		int aMul = (int) (alpha * 255);

		if (hudMode < 2) {
			// PERF: пересоздаём текст только когда значения изменились
			int ptsV = ClientData.points();
			if (hudPointsSeq == null || ptsV != hudPoints) {
				hudPoints = ptsV;
				hudPointsSeq = Component.translatable("screen.createtree.points", ptsV).getVisualOrderText();
			}
			int exp = ClientData.exp();
			int per = Math.max(1, ClientData.expPerPoint());
			if (hudExpSeq == null || exp != hudExp || per != hudPer) {
				hudExp = exp;
				hudPer = per;
				hudExpSeq = Component.translatable("screen.createtree.exp", exp, per).getVisualOrderText();
			}

			gui.fill(0, 0, width, 30, (int) (0xC0 * alpha) << 24 | 0x070B18);
			gui.drawString(font, title, 8, 5, mulAlpha(0xFFE7C3, aMul), true);
			gui.drawString(font, hudPointsSeq, 8, 17, mulAlpha(0xFFE070, aMul), true);

			int barW = Math.min(200, width / 3);
			int barX = width / 2 - barW / 2;
			gui.fill(barX - 1, 9, barX + barW + 1, 16, mulAlpha(0xFF000000, aMul));
			gui.fill(barX, 10, barX + barW, 15, mulAlpha(0xFF141A2E, aMul));
			int fill = (int) (barW * Mth.clamp((float) exp / per, 0f, 1f));
			gui.fill(barX, 10, barX + fill, 15, mulAlpha(0xFF7FE7A3, aMul));
			gui.drawCenteredString(font, hudExpSeq, width / 2, 19, mulAlpha(0x9FE8B0, aMul));

			ResourceLocation cItem = ClientData.contractItem();
			if (cItem != null) {
				if (!cItem.equals(hudContractItem)) {
					hudContractItem = cItem;
					hudContractStack = new ItemStack(BuiltInRegistries.ITEM.get(cItem));
					hudContractSeq = null;
				}
				int cp = ClientData.contractProgress(), ct = ClientData.contractTarget(), cr = ClientData.contractReward();
				if (hudContractSeq == null || cp != hudCP || ct != hudCT || cr != hudCR) {
					hudCP = cp;
					hudCT = ct;
					hudCR = cr;
					hudContractSeq = Component.translatable("screen.createtree.contract", cp, ct, cr).getVisualOrderText();
				}
				int cx = width - 8;
				int cy = 7;
				gui.renderItem(hudContractStack, cx - 16, cy);
				gui.drawString(font, hudContractSeq, cx - 20 - font.width(hudContractSeq), cy + 4, mulAlpha(0xFFD0A0, aMul), true);
			}
		}

		float hintA = hintAlpha();
		if (hudMode < 1 && hintA > 0.01f) {
			int ha = (int) (aMul * hintA);
			if (hintSeq == null || hintForWidth != width) {
				Component hint = Component.translatable("screen.createtree.hint");
				hintSeq = hint.getVisualOrderText();
				hintWrap = font.width(hint) > width - 20;
				hintL1 = null;
				hintL2 = null;
				if (hintWrap) {
					String s = hint.getString();
					int cut = s.indexOf(" - ", s.length() / 3);
					if (cut > 0) {
						hintL1 = s.substring(0, cut);
						hintL2 = s.substring(cut + 3);
					} else {
						hintL1 = s;
					}
				}
				hintForWidth = width;
			}
			if (hintWrap) {
				gui.fill(0, height - 34, width, height, mulAlpha(0xC0070B18, ha));
				if (hintL2 != null) {
					gui.drawCenteredString(font, hintL1, width / 2, height - 31, mulAlpha(0x8A90A8, ha));
					gui.drawCenteredString(font, hintL2, width / 2, height - 21, mulAlpha(0x8A90A8, ha));
				} else {
					gui.drawCenteredString(font, hintL1, width / 2, height - 26, mulAlpha(0x8A90A8, ha));
				}
			} else {
				gui.fill(0, height - 26, width, height, mulAlpha(0xC0070B18, ha));
				gui.drawCenteredString(font, hintSeq, width / 2, height - 19, mulAlpha(0x8A90A8, ha));
			}
		}
	}

	
	private static int mulAlpha(int argb, int aMul) {
		int a = ((argb >>> 24) * aMul) / 255;
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	private void renderNodeTooltip(GuiGraphics gui, Node node, int index, int mouseX, int mouseY) {
		List<Component> lines = new ArrayList<>();
		lines.add(node.icon.isEmpty() ? Component.literal(node.entry.item().toString()) : node.icon.getHoverName());
		lines.add(Component.translatable("category.createtree." + node.entry.category().jsonName())
			.withStyle(s -> s.withColor(categoryColor(node.entry.category()))));

		if (!node.entry.unlocks().isEmpty()) {
			lines.add(Component.translatable("screen.createtree.also_unlocks").withStyle(s -> s.withColor(0x9AD0FF)));
			int shownUnlocks = 0;
			for (ResourceLocation uid : node.entry.unlocks()) {
				if (shownUnlocks++ >= 6) {
					lines.add(Component.literal("  +" + (node.entry.unlocks().size() - 6) + " ...")
						.withStyle(s -> s.withColor(0x8A9AB0)));
					break;
				}
				var bonus = new net.minecraft.world.item.ItemStack(
					net.minecraft.core.registries.BuiltInRegistries.ITEM.get(uid));
				lines.add(Component.literal("  ").append(
					bonus.isEmpty() ? Component.literal(uid.toString()) : bonus.getHoverName())
					.withStyle(s -> s.withColor(0xBFD0E8)));
			}
		}
		if (stUnlocked[index]) {
			lines.add(Component.translatable("screen.createtree.unlocked").withStyle(s -> s.withColor(0x70FF70)));
			lines.add(Component.translatable("screen.createtree.exp_per_craft", node.entry.exp())
				.withStyle(s -> s.withColor(0x9FE8B0)));
		} else if (!stParentsOk[index]) {
			lines.add(Component.translatable("screen.createtree.requires_any").withStyle(s -> s.withColor(0xFF9A9A)));
		} else {
			int special = ClientData.discountPrice(node.entry.item());
			if (special >= 0 && special < node.entry.cost()) {

				lines.add(Component.translatable("screen.createtree.cost", node.entry.cost(), node.entry.exp())
					.withStyle(s -> s.withColor(0x7A7A7A).withStrikethrough(true)));
				lines.add(Component.translatable("screen.createtree.special_price", special)
					.withStyle(s -> s.withColor(0xFF8AE0)));
			} else {
				lines.add(Component.translatable("screen.createtree.cost", node.entry.cost(), node.entry.exp()));
			}
			lines.add(stAffordable[index]
				? Component.translatable("screen.createtree.click_to_unlock").withStyle(s -> s.withColor(0xFFE070))
				: Component.translatable("message.createtree.not_enough_points").withStyle(s -> s.withColor(0xFF7A7A)));
		}
		gui.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
	}

	
	private static int effectiveCost(SkillEntry entry) {
		int special = ClientData.discountPrice(entry.item());
		return special >= 0 ? special : entry.cost();
	}

	private int categoryColor(Category c) {
		return switch (c) {
			case LIGHT -> 0x9FE8A3;
			case MEDIUM -> 0xFFE070;
			case COMPLEX -> 0xFF9A9A;
		};
	}

	private void playSound(SoundEvent event, float pitch) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch));
	}


	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button))
			return true;

		if (phase == 0) {
			if (button == 0) {
				float bob = 6f * Mth.sin(Util.getMillis() / 600f);
				if (Math.hypot(mouseX - width / 2, mouseY - (height / 2 + bob)) <= WRENCH_CLICK_R + 8) {
					ModNetwork.sendToServer(new IgnitePayload());
					playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
					if (!ClientData.sunIgnited()) {

						ClientData.setSunIgnitedLocal();
						phase = 1;
						birthStartMs = Util.getMillis();
					}
				}
			}
			return true;
		}
		if (phase == 1)
			return true;
		if (button == 0 || button == 1 || button == 2) {
			dragging = true;
			moved = 0;
			dragStartX = mouseX;
			dragStartY = mouseY;
			dragPanX = panX;
			dragPanY = panY;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging && button == 0 && moved < 5) {
			dragging = false;
			if (focusK == -2) {

				int body = bodyAt(mouseX, mouseY);
				if (body != -2) {
					setFocus(body);
					playSound(SoundEvents.UI_BUTTON_CLICK.value(), body < 0 ? 1.2f : 0.9f);
				}
				return true;
			}

			int idx = nodeAt(mouseX, mouseY);
			if (idx >= 0 && slotInFocus(idx + 1)) {
				Node node = nodes.get(idx);
				if (!stUnlocked[idx] && anyParentUnlocked(idx)) {
					ModNetwork.sendToServer(new UnlockRequestPayload(node.entry.item()));
					playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1f);
				}
				return true;
			}

			int body = bodyAt(mouseX, mouseY);
			if (body != -2 && body != focusK) {
				setFocus(body);
				playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.9f);
			}
			return true;
		}
		if (dragging && button == 0 && moved >= 5) {

			dragging = false;
			return true;
		}
		dragging = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private int nodeAt(double mouseX, double mouseY) {
		for (int i = nodes.size() - 1; i >= 0; i--)
			if (overSlot(mouseX, mouseY, i + 1))
				return i;
		return -1;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
		if (phase != 2)
			return true;
		if (focusK >= -3 && focusK != -2) {

			float max = focusMaxOff();
			focusOffX += (float) dx;
			focusOffY += (float) dy;
			float len = (float) Math.hypot(focusOffX, focusOffY);
			if (len > max && len > 0) {
				focusOffX *= max / len;
				focusOffY *= max / len;
			}
			return true;
		}
		if (dragging) {
			panX = (float) (dragPanX + (mouseX - dragStartX));
			panY = (float) (dragPanY + (mouseY - dragStartY));
			moved = Math.max(moved, Math.hypot(mouseX - dragStartX, mouseY - dragStartY));
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (phase != 2)
			return true;
		if (focusK >= -3 && focusK != -2) {

			float oldZoom = focusZoom;
			focusZoom = Mth.clamp(focusZoom * (scrollY > 0 ? 1.15f : 1 / 1.15f), FOCUS_MIN_ZOOM, 2.5f);
			if (oldZoom > 0) {
				float k = focusZoom / oldZoom;
				focusOffX *= k;
				focusOffY *= k;
			}
			zoom = focusZoom;
			panX = width / 2f - focusCenterX() * zoom + focusOffX;
			panY = height / 2f - focusCenterY() * zoom + focusOffY;
			return true;
		}
		float old = zoom;
		zoom = Mth.clamp(zoom * (scrollY > 0 ? 1.15f : 1 / 1.15f), 0.15f, 2.5f);
		panX = (float) (mouseX - (mouseX - panX) * (zoom / old));
		panY = (float) (mouseY - (mouseY - panY) * (zoom / old));
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (closing)
			return true;
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_C) {
			if (phase != 2)
				return true;
			focusK = -2;
			zoom = 1f;
			centerView();
			return true;
		}
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_SUBTRACT) {
			hudMode = (hudMode + 1) % 3;
			return true;
		}
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			if (phase != 2) {

				closing = true;
				closeStartMs = Util.getMillis();
				playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f);
				return true;
			}
			if (focusK >= -3 && focusK != -2) {
				focusK = -2;
				return true;
			}

			closing = true;
			closeStartMs = Util.getMillis();
			playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		if (closing) {
			super.onClose();
			return;
		}

		closing = true;
		closeStartMs = Util.getMillis();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private record Node(SkillEntry entry, ItemStack icon) {
	}

	private static final class Spark {
		final float x, y, vx, vy;
		final long born;
		final int life;

		Spark(float x, float y, float vx, float vy, int life) {
			this.x = x;
			this.y = y;
			this.vx = vx;
			this.vy = vy;
			this.life = life;
			this.born = Util.getMillis();
		}
	}
}
