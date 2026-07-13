package com.amr3d.preview.pro

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class STLRenderer : GLSurfaceView.Renderer {

    // --- Shaders مع دعم اتجاه الإضاءة ---
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uNormalMatrix;
        uniform mat4 uModelMatrix;
        attribute vec4 vPosition;
        attribute vec3 vNormal;
        varying vec3 fNormal;
        varying highp vec3 fPosition;
        varying highp vec3 fWorldPos;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fNormal    = normalize((uNormalMatrix * vec4(vNormal, 0.0)).xyz);
            fPosition  = vPosition.xyz;
            fWorldPos  = (uModelMatrix * vPosition).xyz;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec3 fNormal;
        varying highp vec3 fPosition;
        varying highp vec3 fWorldPos;
        uniform vec4 uColor;
        uniform vec3 uLightDir;
        uniform int  uMaterial;
        uniform float uLightAngleDeg;
        // بيتحسب من نصف قطر الموديل الفعلي (1/modelRadius) بدل رقم ثابت (كان 0.015)
        // عشان حبيبات الخشب وعروق الرخام تبان بنفس النسبة والوضوح بغض النظر عن حجم
        // الموديل الحقيقي (مم صغيرة أو أمتار) — قبل كده كانت بتتلخبط (تتكدّس أو تختفي)
        // لأي موديل مش قريب من الحجم اللي الرقم الثابت كان متظبّط عليه.
        uniform float uPatternScale;

        // ═══ تلوين سريع (Quick Tint) — vec3، (-1,-1,-1) يعني "معطّل". لو مفعّل بيتخلط
        // مع لون الخامة الحالية بنسبة 60% عشان ملمس الخامة (خشب/رخام/إلخ) يفضل واضح تحته ═══
        uniform vec3 uQuickTint;

        // ═══ Hash & Noise ═══
        float hash(highp vec3 p) {
            p = fract(p * vec3(443.897, 397.297, 491.187));
            p += dot(p.zxy, p.yxz + 19.19);
            return fract(p.x * p.y * p.z);
        }
        float hash2(vec2 p) {
            return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.545);
        }
        float noise3(highp vec3 p) {
            vec3 i = floor(p); vec3 f = fract(p);
            f = f*f*(3.0-2.0*f);
            return mix(
                mix(mix(hash(i),           hash(i+vec3(1,0,0)), f.x),
                    mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x), f.y),
                mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),
                    mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x), f.y), f.z);
        }
        float fbm3(highp vec3 p) {
            float v=0.0,a=0.5;
            for(int i=0;i<5;i++){v+=a*noise3(p);p*=2.1;a*=0.5;}
            return v;
        }

        // ═══ Triplanar UV Mapping (Shrink Wrap) ═══
        vec2 triplanarUV(highp vec3 worldPos, vec3 N, float scale) {
            vec3 blend = abs(N);
            blend = max(blend - 0.2, 0.0);
            blend /= (blend.x + blend.y + blend.z + 0.001);
            vec2 uvX = worldPos.yz * scale;
            vec2 uvY = worldPos.xz * scale;
            vec2 uvZ = worldPos.xy * scale;
            return uvX*blend.x + uvY*blend.y + uvZ*blend.z;
        }

        float triNoise(highp vec3 worldPos, vec3 N, float scale) {
            vec3 blend = abs(N);
            blend = max(blend - 0.2, 0.0);
            blend /= (blend.x + blend.y + blend.z + 0.001);
            float nx = fbm3(vec3(worldPos.yz * scale, 0.0));
            float ny = fbm3(vec3(worldPos.xz * scale, 0.0));
            float nz = fbm3(vec3(worldPos.xy * scale, 0.0));
            return nx*blend.x + ny*blend.y + nz*blend.z;
        }

        void main() {
            vec3 N = normalize(fNormal);
            vec3 L = normalize(uLightDir);
            vec3 V = normalize(vec3(0.0, 0.0, 1.0) - fPosition * 0.008);
            vec3 H = normalize(L + V);
            highp vec3 pos = fWorldPos * uPatternScale;

            vec3 col = uColor.rgb;
            float rough = 0.5;
            float metal = 0.0;
            float shine = 32.0;
            float spec  = 0.5;
            float amb   = 0.45;

            // ═══ بلاستيك ═══
            if (uMaterial == 0) {
                col   = uColor.rgb;
                rough = 0.35; metal = 0.0; shine = 32.0; spec = 0.35; amb = 0.42;
            }
            // ═══ معدن (Triplanar brushed) ═══
            else if (uMaterial == 1) {
                vec2 uv = triplanarUV(pos, N, 6.0);
                float scratch = noise3(vec3(uv * vec2(1.0, 20.0), 0.3)) * 0.07;
                float aniso   = noise3(vec3(uv.x * 0.5, uv.y * 30.0, 1.0)) * 0.04;
                col   = uColor.rgb + vec3(scratch + aniso);
                rough = 0.18; metal = 1.0; shine = 160.0; spec = 1.4; amb = 0.28;
            }
            // ═══ خشب (Triplanar grain) ═══
            else if (uMaterial == 2) {
                float grain = triNoise(pos, N, 4.0);
                // كانت rings بتتحسب بس من length(pos.xz) — يعني حلقات دايرية حوالين محور Y
                // للموديل كله بغض النظر عن اتجاهه الحقيقي، فكانت بتبان صح بالصدفة بس
                // في موديلات أسطوانية واقفة على Y وغلط في أي حاجة تانية. دلوقتي بتتحسب لكل
                // مستوى إسقاط (triplanar) زي باقي الخامات، فبتبان صح من أي اتجاه.
                vec2 uvWood = triplanarUV(pos, N, 3.0);
                float rings = sin((length(uvWood) * 22.0) + grain * 8.0) * 0.5 + 0.5;
                rings = pow(rings, 1.4);
                float fiber = noise3(pos * vec3(1.0, 0.1, 1.0) * 12.0) * 0.12;
                // درجات لون أدفى وتباين أوضح بين الحبيبات الفاتحة والغامقة عشان
                // يبان خشب حقيقي مش بقعة لونين مسطحة
                vec3 wLight = vec3(0.80, 0.58, 0.28);
                vec3 wMid   = vec3(0.58, 0.36, 0.15);
                vec3 wDark  = vec3(0.30, 0.16, 0.06);
                vec3 wBase  = mix(wDark, wMid, smoothstep(0.0, 0.55, rings + fiber));
                col   = mix(wBase, wLight, smoothstep(0.55, 1.0, rings + fiber));
                rough = 0.78; metal = 0.0; shine = 14.0; spec = 0.18; amb = 0.5;
            }
            // ═══ رخام (Triplanar veins) ═══
            else if (uMaterial == 3) {
                float n1  = triNoise(pos, N, 2.0);
                float n2  = triNoise(pos * 1.7 + vec3(3.1), N, 3.5);
                // عروق أرفع وأكتر تفرّع (بإضافة طبقة noise تالتة) بدل خط واحد سميك
                // بيتكرر بانتظام — كان بيبان زي خطوط متوازية مش عروق رخام طبيعية
                float n3  = triNoise(pos * 3.1 + vec3(7.7), N, 6.0);
                float vein= sin((n1 + n2 * 0.6 + n3 * 0.25) * 14.0) * 0.5 + 0.5;
                vein = smoothstep(0.55, 0.82, vein);
                vec3 mBase = vec3(0.95, 0.93, 0.90);
                vec3 mVein = vec3(0.35, 0.33, 0.34);
                col   = mix(mBase, mVein, vein * 0.75);
                rough = 0.15; metal = 0.02; shine = 120.0; spec = 1.1; amb = 0.5;
            }
            // ═══ نحاس (Triplanar patina) ═══
            else if (uMaterial == 4) {
                float pat  = triNoise(pos, N, 3.5);
                float worn = triNoise(pos * 2.0 + vec3(1.7), N, 5.0) * 0.15;
                vec3 cBase  = vec3(0.82, 0.47, 0.15);
                vec3 cPatina= vec3(0.28, 0.58, 0.42);
                col   = mix(cBase, cPatina, pat * 0.35) + vec3(worn, 0.0, 0.0);
                rough = 0.38; metal = 0.88; shine = 90.0; spec = 1.0; amb = 0.38;
            }
            // ═══ كربون (Triplanar weave) ═══
            else if (uMaterial == 5) {
                vec2 uv = triplanarUV(pos, N, 10.0);
                vec2 g  = uv * 8.0;
                vec2 gi = floor(g); vec2 gf = fract(g);
                float weave = mod(gi.x + gi.y, 2.0) < 1.0 ? gf.x : gf.y;
                float shine2= smoothstep(0.3, 0.7, weave);
                vec3 cDark  = vec3(0.05, 0.05, 0.07);
                vec3 cLight = vec3(0.20, 0.20, 0.26);
                col   = mix(cDark, cLight, shine2 * 0.6);
                rough = 0.25; metal = 0.65; shine = 110.0; spec = 1.1; amb = 0.22;
            }
            // ═══ ذهب ═══
            else if (uMaterial == 6) {
                float n = triNoise(pos, N, 4.0) * 0.12;
                col   = vec3(0.95, 0.72, 0.04) + vec3(n*0.3, n*0.2, 0.0);
                rough = 0.12; metal = 1.0; shine = 200.0; spec = 1.6; amb = 0.32;
            }
            // ═══ مطاط ═══
            else if (uMaterial == 7) {
                float bump = triNoise(pos, N, 8.0) * 0.04;
                col   = uColor.rgb * (0.85 + bump);
                rough = 0.97; metal = 0.0; shine = 4.0; spec = 0.04; amb = 0.52;
            }

            // ═══ تلوين سريع فوق أي خامة مختارة ═══
            if (uQuickTint.r >= 0.0) {
                col = mix(col, uQuickTint, 0.6);
            }

            // ═══ PBR Lighting ═══
            float NdotL  = max(dot(N, L), 0.0);
            float NdotH  = max(dot(N, H), 0.0);
            float NdotV  = max(dot(N, V), 0.0);
            float fresnel= pow(1.0 - NdotV, 4.0);
            vec3  specCol= mix(vec3(0.04), col, metal);

            vec3 ambient  = col * amb;
            vec3 diffuse  = col * NdotL * (1.0 - metal) * 0.78;
            vec3 specular = specCol * pow(NdotH, shine) * spec;
            vec3 rim      = col * fresnel * (0.15 + metal * 0.25);

            // ضوء ثانوي خفيف
            float NdotL2  = max(dot(N, normalize(vec3(-0.4,-0.8,0.2))), 0.0);
            vec3  fill    = col * NdotL2 * 0.10;

            vec3 result = ambient + diffuse + specular + rim + fill;
            result = result / (result + vec3(0.6));  // tone mapping بسيط
            result = pow(result, vec3(0.88));         // gamma تقريبي
            gl_FragColor = vec4(result, uColor.a);
        }
    """.trimIndent()

    private val lineVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = 14.0;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    // ═══ Shader ظل ملامس (contact shadow) بسيط تحت الموديل — quad شفاف بتدرّج دائري غامق ═══
    private val shadowVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            vUV = aUV;
        }
    """.trimIndent()

    private val shadowFragmentShaderCode = """
        precision mediump float;
        varying vec2 vUV;
        void main() {
            float d = length(vUV);
            float alpha = clamp(1.0 - d, 0.0, 1.0);
            alpha = pow(alpha, 2.2) * 0.55;
            gl_FragColor = vec4(0.0, 0.0, 0.0, alpha);
        }
    """.trimIndent()

    private var meshProgram = 0
    private var lineProgram = 0
    private var shadowProgram = 0

    // CPU-side buffers (nulled after upload to GPU)
    private var vertexBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var wireframeBuffer: FloatBuffer? = null
    private var wireframeVertexCount = 0
    private var vertexCountToDraw = 0

    // VBO handles — data lives on GPU after upload
    private val vboIds = IntArray(3) // [0]=vertex [1]=normal [2]=wireframe
    private var vboReady = false
    private var pendingModel: STLModel? = null

    // جودة العرض من الإعدادات: 0=منخفضة 1=متوسطة 2=عالية
    @Volatile var qualityLevel: Int = 2

    @Volatile var wireframeMode = false

    /** أرضية شبكية اختيارية (Grid Floor) — تظهر أسفل الموديل لو مفعّلة من شريط الأدوات */
    @Volatile var showGridFloor = false

    /** بيتفعّل بس أثناء تدوير الموديل بإصبع واحد — بيوريه للمستخدم مركز الدوران (pivot)
     * اللي الموديل بيلف حواليه، عشان يعرف يتحكم في الاتجاه بشكل مقصود بدل ما يحس إنه
     * بيلف "من غير مرجعية". بيختفي تاني لما الإصبع يترفع. */
    @Volatile var showPivotIndicator = false

    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    @Volatile var rotationX = -25f
    @Volatile var rotationY = 35f
    @Volatile var scaleFactor = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    // اتجاه الإضاءة - قابل للتغيير من الـ slider
    @Volatile
    var lightAngle = 45f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
        } // زاوية الإضاءة من 0 إلى 360

    private var modelCenter = floatArrayOf(0f, 0f, 0f)
    private var modelRadius = 1f

    /** لو المستخدم عمل تاب على نقطة في سطح الموديل (خارج وضع القياس)، بتتحط هنا
     * كمركز دوران جديد بدل مركز الـ bounding box الافتراضي (modelCenter). بترجع
     * null لما المستخدم يدوس Reset View. الـ setter بيعمل تعويض فوري على panX/panY
     * عشان الموديل يفضل في نفس مكانه على الشاشة لحظة تغيير المركز — بس الدوران اللي
     * بعد كده هو اللي هيبقى حوالين النقطة الجديدة. */
    @Volatile var pivotOverride: FloatArray? = null
        set(value) {
            if (value != null) {
                val oldPivot = field ?: modelCenter
                val rot = FloatArray(16)
                Matrix.setIdentityM(rot, 0)
                Matrix.rotateM(rot, 0, rotationX, 1f, 0f, 0f)
                Matrix.rotateM(rot, 0, rotationY, 0f, 1f, 0f)
                val diff = floatArrayOf(
                    value[0] - oldPivot[0], value[1] - oldPivot[1], value[2] - oldPivot[2], 0f
                )
                val delta = FloatArray(4)
                Matrix.multiplyMV(delta, 0, rot, 0, diff, 0)
                val panScale = (if (modelRadius > 0f) modelRadius else 1f) * 1.4f / scaleFactor
                panX += delta[0] / panScale
                panY += delta[1] / panScale
            }
            field = value
        }

    // CopyOnWriteArrayList بدل ArrayList - thread-safe
    private val measurementPoints = CopyOnWriteArrayList<FloatArray>()
    @Volatile private var previewPoint: FloatArray? = null

    /** بتتحدث لحظياً أثناء سحب الإصبع بعد اختيار النقطة الأولى — عشان الخط والمسافة يتحركوا مع الإصبع */
    fun setPreviewMeasurementPoint(point: FloatArray?) {
        previewPoint = point
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    var modelColor = floatArrayOf(0.45f, 0.75f, 0.95f, 1.0f)

    fun setModelColor(r: Float, g: Float, b: Float) { modelColor = floatArrayOf(r, g, b, 1.0f) }

    // نظام المواد
    enum class Material(val id: Int, val nameAr: String, val defaultColor: FloatArray) {
        PLASTIC(0, "بلاستيك", floatArrayOf(0.08f, 0.42f, 0.78f)),
        METAL  (1, "معدن",    floatArrayOf(0.78f, 0.78f, 0.82f)),
        WOOD   (2, "خشب",     floatArrayOf(0.55f, 0.32f, 0.12f)),
        MARBLE (3, "رخام",    floatArrayOf(0.90f, 0.88f, 0.85f)),
        COPPER (4, "نحاس",    floatArrayOf(0.80f, 0.45f, 0.15f)),
        CARBON (5, "كربون",   floatArrayOf(0.12f, 0.12f, 0.14f)),
        GOLD   (6, "ذهب",     floatArrayOf(0.95f, 0.72f, 0.04f)),
        RUBBER (7, "مطاط",    floatArrayOf(0.10f, 0.10f, 0.10f));

        /** اسم الخامة مترجَم حسب لغة التطبيق الحالية (بدل nameAr الثابت بالعربي) */
        fun localizedName(context: android.content.Context): String {
            val resId = when (this) {
                PLASTIC -> R.string.material_plastic
                METAL   -> R.string.material_metal
                WOOD    -> R.string.material_wood
                MARBLE  -> R.string.material_marble
                COPPER  -> R.string.material_copper
                CARBON  -> R.string.material_carbon
                GOLD    -> R.string.material_gold
                RUBBER  -> R.string.material_rubber
            }
            return context.getString(resId)
        }
    }

    @Volatile var currentMaterial = Material.PLASTIC

    /** لون تلوين سريع (Quick Tint) فوق أي خامة حالية — null يعني معطّل (يرجع لون الخامة الطبيعي) */
    @Volatile var quickTint: FloatArray? = null
    fun setQuickTint(r: Float, g: Float, b: Float) { quickTint = floatArrayOf(r, g, b) }
    fun clearQuickTint() { quickTint = null }

    fun setMaterial(material: Material) {
        currentMaterial = material
        setModelColor(material.defaultColor[0], material.defaultColor[1], material.defaultColor[2])
    }
    fun getCurrentModelMatrix(): FloatArray = modelMatrix.copyOf()
    fun getCurrentViewMatrix(): FloatArray = viewMatrix.copyOf()
    fun getCurrentProjectionMatrix(): FloatArray = projectionMatrix.copyOf()
    fun getSurfaceWidth(): Int = surfaceWidth
    fun getSurfaceHeight(): Int = surfaceHeight

    private var currentModel: STLModel? = null
    fun getModel(): STLModel? = currentModel

    fun setModel(model: STLModel) {
        currentModel = model
        pendingModel = model   // يُرفع على GL thread في onDrawFrame أو onSurfaceCreated

        modelCenter = floatArrayOf(
            (model.minBounds[0] + model.maxBounds[0]) / 2f,
            (model.minBounds[1] + model.maxBounds[1]) / 2f,
            (model.minBounds[2] + model.maxBounds[2]) / 2f
        )
        val dx = model.maxBounds[0] - model.minBounds[0]
        val dy = model.maxBounds[1] - model.minBounds[1]
        val dz = model.maxBounds[2] - model.minBounds[2]
        modelRadius = (maxOf(dx, dy, dz) / 2f).let { if (it <= 0f) 1f else it }

        rotationX = -25f; rotationY = 35f; scaleFactor = 1f; panX = 0f; panY = 0f
        pivotOverride = null
        measurementPoints.clear()
        updateProjection()
    }


    /** Uploads model geometry to GPU VBOs using chunked approach to avoid OOM. Called on GL thread. */
    private fun uploadModelToGPU(model: STLModel) {
        val verts = model.vertices
        val norms = model.normals
        vertexCountToDraw = verts.size / 3

        try {
            // رفع vertices مباشرة chunk بـ chunk لتجنب OOM
            val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            vb.asFloatBuffer().put(verts); vb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES20.GL_STATIC_DRAW)
            // تحرير فوري
            @Suppress("NOTHING_TO_INLINE")
            System.gc()

            val nb = ByteBuffer.allocateDirect(norms.size * 4).order(ByteOrder.nativeOrder())
            nb.asFloatBuffer().put(norms); nb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, norms.size * 4, nb, GLES20.GL_STATIC_DRAW)
            System.gc()

            // Wireframe: LOD مع حد أقصى 50K مثلث للـ wireframe
            val triCount = vertexCountToDraw / 3
            val qualityMultiplier = when (qualityLevel) {
                0 -> 4    // منخفضة — أقل تفاصيل، أداء أسرع
                1 -> 2    // متوسطة
                else -> 1 // عالية — كل التفاصيل
            }
            val wireStep = when {
                triCount > 1_000_000 -> 20
                triCount > 500_000   -> 10
                triCount > 200_000   -> 5
                triCount > 50_000    -> 2
                else                 -> 1
            } * qualityMultiplier
            val maxWireTris = minOf((triCount + wireStep - 1) / wireStep, 50_000)
            val wireBytes = maxWireTris * 6 * 3 * 4
            val wb = ByteBuffer.allocateDirect(wireBytes).order(ByteOrder.nativeOrder())
            val wf = wb.asFloatBuffer()
            var wCount = 0; var vSrc = 0; var t = 0
            while (t < triCount && wCount < maxWireTris) {
                val base = vSrc
                if (base + 8 < verts.size) {
                    val ax = verts[base];   val ay = verts[base+1]; val az = verts[base+2]
                    val bx = verts[base+3]; val by = verts[base+4]; val bz = verts[base+5]
                    val cx = verts[base+6]; val cy = verts[base+7]; val cz = verts[base+8]
                    wf.put(ax); wf.put(ay); wf.put(az)
                    wf.put(bx); wf.put(by); wf.put(bz)
                    wf.put(bx); wf.put(by); wf.put(bz)
                    wf.put(cx); wf.put(cy); wf.put(cz)
                    wf.put(cx); wf.put(cy); wf.put(cz)
                    wf.put(ax); wf.put(ay); wf.put(az)
                    wCount++
                }
                t += wireStep; vSrc += wireStep * 9
            }
            wb.position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, wCount * 18 * 4, wb, GLES20.GL_STATIC_DRAW)
            wireframeVertexCount = wCount * 6

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            vertexBuffer = null; normalBuffer = null; wireframeBuffer = null
            vboReady = true

        } catch (e: OutOfMemoryError) {
            // fallback: رسم بدون wireframe بـ CPU buffers مصغرة
            android.util.Log.e("STLRenderer", "OOM in uploadModelToGPU, using CPU fallback")
            val maxVerts = minOf(verts.size, 3_000_000)
            vertexBuffer = ByteBuffer.allocateDirect(maxVerts * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(verts, 0, maxVerts); position(0) }
            normalBuffer = ByteBuffer.allocateDirect(maxVerts * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(norms, 0, maxVerts); position(0) }
            vertexCountToDraw = maxVerts / 3
            wireframeVertexCount = 0
            vboReady = false
        }
    }

    fun addMeasurementPoint(point: FloatArray) {
        measurementPoints.add(point)
        if (measurementPoints.size > 2) measurementPoints.removeAt(0)
        previewPoint = null // النقطة اتثبتت فعلياً، مبقاش محتاجين المعاينة الحية
    }

    fun clearMeasurementPoints() { measurementPoints.clear(); previewPoint = null }
    fun getMeasurementPoints(): List<FloatArray> = measurementPoints.toList()

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        updateClearColor()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        meshProgram = createProgram(vertexShaderCode, fragmentShaderCode)
        lineProgram = createProgram(lineVertexShaderCode, lineFragmentShaderCode)
        shadowProgram = createProgram(shadowVertexShaderCode, shadowFragmentShaderCode)
        // Generate VBO handles
        GLES20.glGenBuffers(3, vboIds, 0)
        // Upload any model that was loaded before GL context was ready
        pendingModel?.let { uploadModelToGPU(it); pendingModel = null }
    }

    var bgColor = floatArrayOf(0.10f, 0.11f, 0.13f)
    fun setBackgroundColor(r: Float, g: Float, b: Float) { bgColor = floatArrayOf(r, g, b); updateClearColor() }
    private fun updateClearColor() { GLES20.glClearColor(bgColor[0], bgColor[1], bgColor[2], 1f) }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateProjection()
    }

    fun updateProjection() {
        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val ratio = surfaceWidth.toFloat() / surfaceHeight.toFloat()
        val safeRadius = if (modelRadius > 0f) modelRadius else 1f
        val orthoHalf = safeRadius * 1.4f / scaleFactor
        val near = -safeRadius * 10f
        val far = safeRadius * 10f
        Matrix.orthoM(projectionMatrix, 0,
            -orthoHalf * ratio, orthoHalf * ratio,
            -orthoHalf, orthoHalf, near, far)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        updateClearColor()

        // أولاً: ارفع أي موديل معلّق — قبل أي فحص أو return
        pendingModel?.let { uploadModelToGPU(it); pendingModel = null }

        if ((!vboReady && vertexBuffer == null) || vertexCountToDraw == 0) return

        updateProjection()

        val camDistance = (if (modelRadius > 0f) modelRadius else 1f) * 5f
        val panScale = (if (modelRadius > 0f) modelRadius else 1f) * 1.4f / scaleFactor

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        val pivot = pivotOverride ?: modelCenter
        Matrix.translateM(modelMatrix, 0, -pivot[0], -pivot[1], -pivot[2])

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.translateM(viewMatrix, 0, panX * panScale, panY * panScale, -camDistance)

        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        Matrix.invertM(normalMatrix, 0, modelMatrix, 0)
        Matrix.transposeM(normalMatrix, 0, normalMatrix, 0)

        if (showGridFloor) drawGridFloor()
        drawContactShadow()
        drawMesh()

        val pts = measurementPoints.toList() // snapshot آمن
        val overlayPts = if (pts.size == 1 && previewPoint != null) pts + previewPoint!! else pts
        if (overlayPts.isNotEmpty()) drawMeasurementOverlay(overlayPts)
        if (showPivotIndicator) drawPivotIndicator()
    }

    /** أرضية شبكية (Grid Floor) اختيارية — بترسم في مستوى XZ عند أسفل الموديل، بتمتد
     * لمسافة تتناسب مع modelRadius. بتترسم قبل الموديل عشان الـ depth test الطبيعي
     * (متفعّلش/مبتتقفلش هنا) يخلي أجزاء الموديل اللي فوقها تغطّيها بصريًا صح. */
    private fun drawGridFloor() {
        GLES20.glUseProgram(lineProgram)
        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        val safeRadius = if (modelRadius > 0f) modelRadius else 1f
        val extent = safeRadius * 3f
        val gy = modelCenter[1] - safeRadius
        val lineCount = 20
        val step = (extent * 2f) / lineCount

        val lines = FloatArray((lineCount + 1) * 12)
        var idx = 0
        for (i in 0..lineCount) {
            val off = -extent + i * step
            // خط موازي لمحور Z (X ثابت)
            lines[idx++] = modelCenter[0] + off; lines[idx++] = gy; lines[idx++] = modelCenter[2] - extent
            lines[idx++] = modelCenter[0] + off; lines[idx++] = gy; lines[idx++] = modelCenter[2] + extent
            // خط موازي لمحور X (Z ثابت)
            lines[idx++] = modelCenter[0] - extent; lines[idx++] = gy; lines[idx++] = modelCenter[2] + off
            lines[idx++] = modelCenter[0] + extent; lines[idx++] = gy; lines[idx++] = modelCenter[2] + off
        }
        val fb = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(lines); position(0) }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, fb)
        GLES20.glUniform4f(colorHandle, 0.42f, 0.44f, 0.5f, 0.35f)
        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lines.size / 3)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    /** ظل ملامس (contact shadow) بسيط تحت الموديل — quad شفاف بتدرّج دائري غامق، بيترسم
     * قبل الموديل نفسه عشان يدّي إحساس عمق/ثقل. depth write متقفل هنا (مش الاختبار)
     * عشان الموديل يترسم فوقه طبيعي جداً بعد كده من غير ما الظل يأثر على depth buffer. */
    private fun drawContactShadow() {
        if (shadowProgram == 0) return
        GLES20.glUseProgram(shadowProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        val positionHandle = GLES20.glGetAttribLocation(shadowProgram, "vPosition")
        val uvHandle = GLES20.glGetAttribLocation(shadowProgram, "aUV")
        val mvpHandle = GLES20.glGetUniformLocation(shadowProgram, "uMVPMatrix")
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        val safeRadius = if (modelRadius > 0f) modelRadius else 1f
        val r = safeRadius * 1.6f
        val gy = modelCenter[1] - safeRadius
        val cx = modelCenter[0]; val cz = modelCenter[2]

        val positions = floatArrayOf(
            cx - r, gy, cz - r,
            cx + r, gy, cz - r,
            cx + r, gy, cz + r,
            cx - r, gy, cz + r
        )
        val uvs = floatArrayOf(
            -1f, -1f,
             1f, -1f,
             1f,  1f,
            -1f,  1f
        )

        val posBuf = ByteBuffer.allocateDirect(positions.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(positions); position(0) }
        val uvBuf = ByteBuffer.allocateDirect(uvs.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(uvs); position(0) }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 0, uvBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(uvHandle)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawMesh() {
        if (wireframeMode) drawWireframe() else drawSolidMesh()
    }

    fun captureFrame(width: Int, height: Int): android.graphics.Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        buffer.rewind(); bitmap.copyPixelsFromBuffer(buffer)
        val matrix = android.graphics.Matrix().apply { postScale(1f, -1f) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
    }

    private fun drawWireframe() {
        if (wireframeVertexCount == 0) return
        GLES20.glUseProgram(lineProgram)
        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")
        GLES20.glEnableVertexAttribArray(positionHandle)
        if (vboReady) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
        } else {
            val buf = wireframeBuffer ?: return
            buf.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, buf)
        }
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(colorHandle, modelColor[0], modelColor[1], modelColor[2], 1f)
        GLES20.glLineWidth(1.5f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, wireframeVertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        if (vboReady) GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawSolidMesh() {
        GLES20.glUseProgram(meshProgram)
        val positionHandle = GLES20.glGetAttribLocation(meshProgram, "vPosition")
        val normalHandle   = GLES20.glGetAttribLocation(meshProgram, "vNormal")
        val mvpHandle      = GLES20.glGetUniformLocation(meshProgram, "uMVPMatrix")
        val modelMatHandle = GLES20.glGetUniformLocation(meshProgram, "uModelMatrix")
        GLES20.glUniformMatrix4fv(modelMatHandle, 1, false, modelMatrix, 0)
        val normalMatrixHandle = GLES20.glGetUniformLocation(meshProgram, "uNormalMatrix")
        val colorHandle = GLES20.glGetUniformLocation(meshProgram, "uColor")
        val lightDirHandle = GLES20.glGetUniformLocation(meshProgram, "uLightDir")
        val materialHandle = GLES20.glGetUniformLocation(meshProgram, "uMaterial")
        val patternScaleHandle = GLES20.glGetUniformLocation(meshProgram, "uPatternScale")
        val quickTintHandle = GLES20.glGetUniformLocation(meshProgram, "uQuickTint")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        if (vboReady) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        } else {
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer ?: return)
            normalBuffer?.position(0)
            GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer ?: return)
        }

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(normalMatrixHandle, 1, false, normalMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, modelColor, 0)
        GLES20.glUniform1i(materialHandle, currentMaterial.id)
        // تطبيع مقياس النقش الإجرائي على نصف قطر الموديل الفعلي (كان رقم ثابت 0.015
        // بيفترض حجم موديل معيّن) — كده الخشب/الرخام بيبانوا صح لأي حجم موديل
        GLES20.glUniform1f(patternScaleHandle, 1f / (if (modelRadius > 0f) modelRadius else 1f))
        val qt = quickTint
        if (qt != null) GLES20.glUniform3f(quickTintHandle, qt[0], qt[1], qt[2])
        else GLES20.glUniform3f(quickTintHandle, -1f, -1f, -1f)

        // حساب اتجاه الإضاءة من الزاوية
        val angleRad = Math.toRadians(lightAngle.toDouble()).toFloat()
        val lx = kotlin.math.cos(angleRad) * 0.7f
        val ly = 0.7f
        val lz = kotlin.math.sin(angleRad) * 0.7f
        GLES20.glUniform3f(lightDirHandle, lx, ly, lz)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCountToDraw)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        if (vboReady) GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawMeasurementOverlay(pts: List<FloatArray>) {
        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        val flat = FloatArray(pts.size * 3)
        pts.forEachIndexed { i, p ->
            flat[i * 3] = p[0]; flat[i * 3 + 1] = p[1]; flat[i * 3 + 2] = p[2]
        }
        val fb = ByteBuffer.allocateDirect(flat.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(flat); position(0) }

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, fb)
        GLES20.glUniform4f(colorHandle, 1f, 0.75f, 0.1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pts.size)

        if (pts.size == 2) {
            GLES20.glUniform4f(colorHandle, 1f, 0.85f, 0.2f, 1f)
            GLES20.glLineWidth(4f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        }

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** بيرسم علامة صغيرة (خط متقاطع + نقطة) عند مركز الدوران الفعلي للموديل — بيتحرك
     * ويتلف مع الموديل نفسه لأنه بيتحسب بنفس الـ mvpMatrix، فالمستخدم يشوف بعينه
     * حوالين أنهي نقطة هو بيلف الموديل وقت السحب بإصبع واحد. */
    private fun drawPivotIndicator() {
        GLES20.glUseProgram(lineProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        val positionHandle = GLES20.glGetAttribLocation(lineProgram, "vPosition")
        val mvpHandle = GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix")
        val colorHandle = GLES20.glGetUniformLocation(lineProgram, "uColor")

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)

        // الطول متناسب مع حجم الموديل عشان يبان واضح في أي حجم — النقطة (0,0,0) هنا
        // هي بالظبط مركز الموديل لأن modelMatrix بيترجم الموديل بحيث مركزه يبقى الأصل
        val len = (if (modelRadius > 0f) modelRadius else 1f) * 0.12f
        val lines = floatArrayOf(
            -len, 0f, 0f,  len, 0f, 0f,
            0f, -len, 0f,  0f, len, 0f,
            0f, 0f, -len,  0f, 0f, len
        )
        val fb = ByteBuffer.allocateDirect(lines.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(lines); position(0) }

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, fb)
        GLES20.glLineWidth(3f)
        GLES20.glUniform4f(colorHandle, 1f, 1f, 1f, 0.9f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)

        val dot = FloatArray(3) // (0,0,0)
        val dotBuffer = ByteBuffer.allocateDirect(dot.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(dot); position(0) }
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, dotBuffer)
        GLES20.glUniform4f(colorHandle, 1f, 0.75f, 0.1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val v = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val f = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, v)
            GLES20.glAttachShader(it, f)
            GLES20.glLinkProgram(it)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(it)
                GLES20.glDeleteProgram(it)
                throw RuntimeException("Program link failed: $log")
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, shaderCode)
            GLES20.glCompileShader(it)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(it)
                GLES20.glDeleteShader(it)
                throw RuntimeException("Shader compile failed: $log")
            }
        }
    }
}
