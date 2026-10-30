package com.example.playerhitbox;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.MappableRingBuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Рисует хитбоксы игроков поверх всего (в том числе сквозь блоки).
 *
 * Схема рендера (собственный конвейер без теста глубины, шейдер линий и
 * событие WorldRenderEvents.END_MAIN) основана на проекте block-highlighter
 * (c) 2026 Lam, лицензия MIT: https://github.com/wdos3/block-highlighter
 */
public final class HitboxRenderer {
	// ===================== НАСТРОЙКИ (меняй здесь) =====================
	/** Дальность в блоках: хитбоксы игроков дальше этого расстояния не рисуются. */
	private static final double RADIUS = 64.0;
	/** Толщина линий. */
	private static final float LINE_WIDTH = 2.5f;
	/** Цвет близких игроков (до NEAR_DISTANCE блоков), формат 0xRRGGBB. */
	private static final int COLOR_NEAR = 0xFF3030;
	/** Цвет дальних игроков, формат 0xRRGGBB. */
	private static final int COLOR_FAR = 0xFFD030;
	/** До какого расстояния игрок считается близким. */
	private static final double NEAR_DISTANCE = 12.0;
	/** Прозрачность линий, 0..255. */
	private static final int ALPHA = 255;
	// ===================================================================

	private static final Identifier PIPELINE_ID = Identifier.of("playerhitbox", "pipeline/through-wall-lines");
	private static final Identifier SHADER_ID = Identifier.of("playerhitbox", "core/through_wall_lines");
	private static final RenderPipeline THROUGH_WALL_LINES = RenderPipelines.register(
			RenderPipeline.builder()
					.withLocation(PIPELINE_ID)
					.withVertexShader(SHADER_ID)
					.withFragmentShader(SHADER_ID)
					.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
					.withUniform("Projection", UniformType.UNIFORM_BUFFER)
					.withUniform("Globals", UniformType.UNIFORM_BUFFER)
					.withBlend(BlendFunction.TRANSLUCENT)
					.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
					.withDepthWrite(false)
					.withCull(false)
					.withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.DrawMode.LINES)
					.build()
	);
	private static final VertexFormat VERTEX_FORMAT = VertexFormats.POSITION_COLOR_NORMAL_LINE_WIDTH;
	private static final VertexFormat.DrawMode DRAW_MODE = VertexFormat.DrawMode.LINES;
	private static final int INITIAL_BUFFER_SIZE = 1 << 16;
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	private static final BufferAllocator ALLOCATOR = new BufferAllocator(INITIAL_BUFFER_SIZE);

	private static MappableRingBuffer vertexBuffer;
	private static int renderedIndexCount;
	private static boolean vertexBufferHasData;

	private HitboxRenderer() {
	}

	public static void initialize() {
		// Рисуем после основного прохода мира, прямо в главный framebuffer,
		// чтобы последующие проходы (террейн, прозрачные блоки) не затёрли линии.
		WorldRenderEvents.END_MAIN.register(HitboxRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!PlayerHitboxClient.isEnabled() || client.world == null || client.player == null) {
			return;
		}

		float tickProgress = client.getRenderTickCounter().getTickProgress(false);
		Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
		double radiusSquared = RADIUS * RADIUS;
		double nearSquared = NEAR_DISTANCE * NEAR_DISTANCE;

		BufferBuilder builder = new BufferBuilder(ALLOCATOR, DRAW_MODE, VERTEX_FORMAT);
		Matrix4f identity = new Matrix4f();

		for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
			if (player == client.player || !player.isAlive()) {
				continue;
			}
			double distanceSquared = client.player.squaredDistanceTo(player);
			if (distanceSquared > radiusSquared) {
				continue;
			}

			// Плавная позиция между тиками, чтобы рамка не дёргалась
			Vec3d pos = player.getLerpedPos(tickProgress);
			Box size = player.getBoundingBox();
			double halfX = size.getLengthX() / 2.0;
			double halfZ = size.getLengthZ() / 2.0;
			double height = size.getLengthY();

			// Все координаты считаем относительно камеры: так линии не дрожат
			// и далеко от центра мира.
			double minX = pos.x - halfX - camera.x;
			double maxX = pos.x + halfX - camera.x;
			double minY = pos.y - camera.y;
			double maxY = pos.y + height - camera.y;
			double minZ = pos.z - halfZ - camera.z;
			double maxZ = pos.z + halfZ - camera.z;

			int rgb = distanceSquared <= nearSquared ? COLOR_NEAR : COLOR_FAR;
			emitBox(builder, identity, minX, minY, minZ, maxX, maxY, maxZ, rgb);
		}

		BuiltBuffer builtBuffer = builder.endNullable();
		try {
			if (builtBuffer == null) {
				renderedIndexCount = 0;
			} else {
				upload(builtBuffer);
			}
		} finally {
			if (builtBuffer != null) {
				builtBuffer.close();
			}
			ALLOCATOR.clear();
		}

		if (renderedIndexCount > 0) {
			draw(client);
		}
	}

	private static void emitBox(BufferBuilder builder, Matrix4f matrix,
			double x0, double y0, double z0, double x1, double y1, double z1, int rgb) {
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		int blue = rgb & 0xFF;

		// Нижняя грань
		line(builder, matrix, x0, y0, z0, x1, y0, z0, red, green, blue);
		line(builder, matrix, x1, y0, z0, x1, y0, z1, red, green, blue);
		line(builder, matrix, x1, y0, z1, x0, y0, z1, red, green, blue);
		line(builder, matrix, x0, y0, z1, x0, y0, z0, red, green, blue);
		// Верхняя грань
		line(builder, matrix, x0, y1, z0, x1, y1, z0, red, green, blue);
		line(builder, matrix, x1, y1, z0, x1, y1, z1, red, green, blue);
		line(builder, matrix, x1, y1, z1, x0, y1, z1, red, green, blue);
		line(builder, matrix, x0, y1, z1, x0, y1, z0, red, green, blue);
		// Вертикальные рёбра
		line(builder, matrix, x0, y0, z0, x0, y1, z0, red, green, blue);
		line(builder, matrix, x1, y0, z0, x1, y1, z0, red, green, blue);
		line(builder, matrix, x1, y0, z1, x1, y1, z1, red, green, blue);
		line(builder, matrix, x0, y0, z1, x0, y1, z1, red, green, blue);
	}

	private static void line(VertexConsumer builder, Matrix4f matrix,
			double ax, double ay, double az, double bx, double by, double bz,
			int red, int green, int blue) {
		// Шейдер линий считает толщину по нормали (направлению отрезка).
		// Нулевая нормаль даёт NaN и линия не рисуется, поэтому нормализуем.
		float nx = (float) (bx - ax);
		float ny = (float) (by - ay);
		float nz = (float) (bz - az);
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (length == 0.0f) {
			return;
		}
		nx /= length;
		ny /= length;
		nz /= length;
		builder.vertex(matrix, (float) ax, (float) ay, (float) az)
				.color(red, green, blue, ALPHA)
				.normal(nx, ny, nz)
				.lineWidth(LINE_WIDTH);
		builder.vertex(matrix, (float) bx, (float) by, (float) bz)
				.color(red, green, blue, ALPHA)
				.normal(nx, ny, nz)
				.lineWidth(LINE_WIDTH);
	}

	private static void upload(BuiltBuffer builtBuffer) {
		ByteBuffer source = builtBuffer.getBuffer();
		int vertexBytes = source.remaining();
		if (vertexBytes == 0) {
			renderedIndexCount = 0;
			return;
		}
		ensureVertexBuffer(vertexBytes);
		if (vertexBufferHasData) {
			vertexBuffer.rotate();
		}
		GpuBuffer gpuVertexBuffer = vertexBuffer.getBlocking();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.writeToBuffer(gpuVertexBuffer.slice(0, vertexBytes), source);

		BuiltBuffer.DrawParameters drawParameters = builtBuffer.getDrawParameters();
		renderedIndexCount = drawParameters.indexCount();
		vertexBufferHasData = true;
	}

	private static void draw(MinecraftClient client) {
		RenderSystem.ShapeIndexBuffer indexBuffer = RenderSystem.getSequentialBuffer(DRAW_MODE);
		GpuBuffer gpuIndexBuffer = indexBuffer.getIndexBuffer(renderedIndexCount);
		// Вершины уже заданы относительно камеры, поэтому сдвигать матрицу не нужно.
		Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
				modelView, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
		Framebuffer target = client.getFramebuffer();
		try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "Player Hitbox lines",
				target.getColorAttachmentView(), OptionalInt.empty(), target.getDepthAttachmentView(), OptionalDouble.empty())) {
			renderPass.setPipeline(THROUGH_WALL_LINES);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", transforms);
			renderPass.setVertexBuffer(0, vertexBuffer.getBlocking());
			renderPass.setIndexBuffer(gpuIndexBuffer, indexBuffer.getIndexType());
			renderPass.drawIndexed(0, 0, renderedIndexCount, 1);
		}
	}

	private static void ensureVertexBuffer(int requiredBytes) {
		if (vertexBuffer != null && vertexBuffer.size() >= requiredBytes) {
			return;
		}
		if (vertexBuffer != null) {
			vertexBuffer.close();
		}
		int size = INITIAL_BUFFER_SIZE;
		while (size < requiredBytes) {
			size = Math.multiplyExact(size, 2);
		}
		vertexBuffer = new MappableRingBuffer(() -> "Player Hitbox vertex buffer",
				GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX, size);
		vertexBufferHasData = false;
	}

	public static void close() {
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
		renderedIndexCount = 0;
		vertexBufferHasData = false;
		ALLOCATOR.close();
	}
}
