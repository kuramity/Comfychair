package sh.hnet.comfychair.viewmodel.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import sh.hnet.comfychair.model.LoraSelection
import sh.hnet.comfychair.model.SeedMode
import sh.hnet.comfychair.model.WorkflowCapabilities
import sh.hnet.comfychair.viewmodel.ImageToVideoUiState
import sh.hnet.comfychair.viewmodel.ImageToVideoViewMode
import sh.hnet.comfychair.viewmodel.TextToVideoUiState
import sh.hnet.comfychair.viewmodel.withVideoSnapshot
import sh.hnet.comfychair.workflow.dynamic.DynamicParam
import sh.hnet.comfychair.workflow.dynamic.DynamicParamControl

/**
 * [VideoStateSnapshot] 与两侧 `withVideoSnapshot` 回拷的完整性钉子（B4.2-B）。
 *
 * 为什么需要：基类共享函数统一走「快照改字段 → withVideoSnapshot 一次性回拷」。
 * `of()` 漏拷一个字段 → 共享写入时该字段被快照缺省值覆盖（丢用户数据）；
 * `withVideoSnapshot` 漏拷一个字段 → 共享写入不生效。两者编译器都查不出来，
 * 这里用「全字段哨兵值 + 逐字段断言」把它们钉死：
 *
 * 1. 快照回转（of → withVideoSnapshot）后状态必须逐字段相等（钉 of 与回拷的并集）；
 * 2. 快照每个字段改写哨兵值后，回拷结果每个字段都必须变成哨兵值（钉 withVideoSnapshot 全覆盖）；
 * 3. ITV 独有字段（图位 / 音频 / 上传状态等）不在快照里，回拷后必须原样保留。
 */
class VideoStateSnapshotRoundTripTest {

    private val capabilities = WorkflowCapabilities(hasPositivePrompt = true, hasCfg = true)

    private val dynamicParam = DynamicParam(
        placeholder = "ph",
        nodeId = "12",
        classType = "KSampler",
        inputName = "steps",
        control = DynamicParamControl.TextField(multiline = false, default = null)
    )

    /** 文生视频：全字段都有可辨识的非缺省值 */
    private fun fullTtvState() = TextToVideoUiState(
        previewBitmap = null,
        currentVideoUri = null,
        selectedWorkflow = "wf",
        selectedWorkflowId = "wf-id",
        availableWorkflows = listOf(
            VideoWorkflowItem(id = "wf-id", name = "wf", displayName = "wf", type = sh.hnet.comfychair.WorkflowType.TTV)
        ),
        workflowPlaceholders = setOf("positive_prompt", "steps"),
        dynamicParams = listOf(dynamicParam),
        dynamicParamValues = mapOf("ph" to "v"),
        dynamicParamsRegistryReady = true,
        selectedCheckpoint = "ckpt",
        selectedUnet = "unet",
        selectedLoraName = "lora",
        loraStrength = 0.7f,
        selectedHighnoiseUnet = "h-unet",
        selectedLownoiseUnet = "l-unet",
        selectedHighnoiseLora = "h-lora",
        selectedLownoiseLora = "l-lora",
        selectedVae = "vae",
        selectedClip = "clip",
        selectedClip1 = "clip1",
        selectedClip2 = "clip2",
        selectedClip3 = "clip3",
        selectedClip4 = "clip4",
        selectedTextEncoder = "te",
        selectedLatentUpscaleModel = "lum",
        availableCheckpoints = listOf("ckpt"),
        availableUnets = listOf("unet"),
        availableLoras = listOf("lora"),
        availableVaes = listOf("vae"),
        availableClips = listOf("clip"),
        availableUpscaleMethods = listOf("nearest-exact"),
        availableTextEncoders = listOf("te"),
        availableLatentUpscaleModels = listOf("lum"),
        availableSamplers = listOf("euler"),
        availableSchedulers = listOf("normal"),
        availableAspectRatios = listOf("16:9"),
        availableSpeedModes = listOf("fast"),
        filteredCheckpoints = listOf("ckpt"),
        filteredUnets = listOf("unet"),
        filteredLoras = listOf("lora"),
        filteredVaes = listOf("vae"),
        filteredClips = listOf("clip"),
        filteredClips1 = listOf("clip1"),
        filteredClips2 = listOf("clip2"),
        filteredClips3 = listOf("clip3"),
        filteredClips4 = listOf("clip4"),
        filteredTextEncoders = listOf("te"),
        filteredLatentUpscaleModels = listOf("lum"),
        width = "832",
        height = "480",
        megapixels = "1.0",
        length = "33",
        fps = "16",
        steps = "20",
        cfg = "7.0",
        sampler = "euler",
        scheduler = "normal",
        aspectRatio = "16:9",
        speedMode = "fast",
        seedMode = SeedMode.INCREMENT,
        seed = "42",
        denoise = "1.0",
        batchSize = "1",
        upscaleMethod = "nearest-exact",
        scaleBy = "1.5",
        stopAtClipLayer = "-1",
        widthError = null,
        heightError = "高度错",
        megapixelsError = null,
        lengthError = null,
        fpsError = null,
        stepsError = null,
        cfgError = null,
        seedError = null,
        denoiseError = null,
        batchSizeError = null,
        scaleByError = null,
        stopAtClipLayerError = null,
        positivePrompt = "正向词",
        negativePrompt = "负向词",
        deferredCheckpoint = "d-ckpt",
        deferredUnet = "d-unet",
        deferredLoraName = "d-lora",
        deferredHighnoiseUnet = "d-h-unet",
        deferredLownoiseUnet = "d-l-unet",
        deferredHighnoiseLora = "d-h-lora",
        deferredLownoiseLora = "d-l-lora",
        deferredVae = "d-vae",
        deferredClip = "d-clip",
        deferredClip1 = "d-clip1",
        deferredClip2 = "d-clip2",
        deferredClip3 = "d-clip3",
        deferredClip4 = "d-clip4",
        deferredTextEncoder = "d-te",
        deferredLatentUpscaleModel = "d-lum",
        highnoiseLoraChain = listOf(LoraSelection(name = "high", strength = 0.6f)),
        lownoiseLoraChain = listOf(LoraSelection(name = "low", strength = 0.4f)),
        loraChain = listOf(LoraSelection(name = "main", strength = 0.5f)),
        capabilities = capabilities,
        isFetching = true
    )

    /** 图生视频：共享字段全值 + 独有字段全值（独有字段回拷后必须原样保留） */
    private fun fullItvState() = ImageToVideoUiState(
        viewMode = ImageToVideoViewMode.PREVIEW,
        // Bitmap / Uri 无法在 JVM 单测构造（android.jar 桩），空表即可：missed-copy 会被
        // 下面的源码级回拷钉子兜住
        slotImages = emptyMap(),
        imageSlots = emptyList(),
        numberedSlotContainer = null,
        audioSlots = emptyList(),
        slotAudioFiles = emptyMap(),
        numberedAudioContainer = null,
        legacyTimelineUnsupported = true,
        previewBitmap = null,
        currentVideoUri = null,
        selectedWorkflow = "wf-itv",
        selectedWorkflowId = "wf-itv-id",
        availableWorkflows = listOf(
            VideoWorkflowItem(id = "wf-itv-id", name = "wf-itv", displayName = "wf-itv", type = sh.hnet.comfychair.WorkflowType.ITV)
        ),
        workflowPlaceholders = setOf("positive_prompt"),
        dynamicParams = listOf(dynamicParam),
        dynamicParamValues = mapOf("ph" to "itv"),
        dynamicParamsRegistryReady = true,
        selectedCheckpoint = "ckpt-itv",
        selectedUnet = "unet-itv",
        selectedLoraName = "lora-itv",
        loraStrength = 0.8f,
        selectedHighnoiseUnet = "h-unet-itv",
        selectedLownoiseUnet = "l-unet-itv",
        selectedHighnoiseLora = "h-lora-itv",
        selectedLownoiseLora = "l-lora-itv",
        selectedVae = "vae-itv",
        selectedClip = "clip-itv",
        selectedClip1 = "clip1-itv",
        selectedClip2 = "clip2-itv",
        selectedClip3 = "clip3-itv",
        selectedClip4 = "clip4-itv",
        selectedTextEncoder = "te-itv",
        selectedLatentUpscaleModel = "lum-itv",
        availableCheckpoints = listOf("ckpt-itv"),
        availableUnets = listOf("unet-itv"),
        availableLoras = listOf("lora-itv"),
        availableVaes = listOf("vae-itv"),
        availableClips = listOf("clip-itv"),
        availableUpscaleMethods = listOf("nearest-exact"),
        availableTextEncoders = listOf("te-itv"),
        availableLatentUpscaleModels = listOf("lum-itv"),
        availableSamplers = listOf("euler"),
        availableSchedulers = listOf("normal"),
        availableAspectRatios = listOf("16:9"),
        availableSpeedModes = listOf("fast"),
        filteredCheckpoints = listOf("ckpt-itv"),
        filteredUnets = listOf("unet-itv"),
        filteredLoras = listOf("lora-itv"),
        filteredVaes = listOf("vae-itv"),
        filteredClips = listOf("clip-itv"),
        filteredClips1 = listOf("clip1-itv"),
        filteredClips2 = listOf("clip2-itv"),
        filteredClips3 = listOf("clip3-itv"),
        filteredClips4 = listOf("clip4-itv"),
        filteredTextEncoders = listOf("te-itv"),
        filteredLatentUpscaleModels = listOf("lum-itv"),
        width = "848",
        height = "480",
        megapixels = "1.0",
        length = "33",
        fps = "16",
        steps = "20",
        cfg = "7.0",
        sampler = "euler",
        scheduler = "normal",
        aspectRatio = "16:9",
        speedMode = "fast",
        seedMode = SeedMode.DECREMENT,
        seed = "7",
        denoise = "1.0",
        batchSize = "1",
        upscaleMethod = "nearest-exact",
        scaleBy = "1.5",
        stopAtClipLayer = "-1",
        widthError = null,
        heightError = null,
        megapixelsError = null,
        lengthError = null,
        fpsError = null,
        stepsError = null,
        cfgError = null,
        seedError = null,
        denoiseError = null,
        batchSizeError = null,
        scaleByError = null,
        stopAtClipLayerError = null,
        positivePrompt = "itv正向词",
        negativePrompt = "itv负向词",
        deferredCheckpoint = "d-ckpt-itv",
        deferredUnet = "d-unet-itv",
        deferredLoraName = "d-lora-itv",
        deferredHighnoiseUnet = "d-h-unet-itv",
        deferredLownoiseUnet = "d-l-unet-itv",
        deferredHighnoiseLora = "d-h-lora-itv",
        deferredLownoiseLora = "d-l-lora-itv",
        deferredVae = "d-vae-itv",
        deferredClip = "d-clip-itv",
        deferredClip1 = "d-clip1-itv",
        deferredClip2 = "d-clip2-itv",
        deferredClip3 = "d-clip3-itv",
        deferredClip4 = "d-clip4-itv",
        deferredTextEncoder = "d-te-itv",
        deferredLatentUpscaleModel = "d-lum-itv",
        highnoiseLoraChain = listOf(LoraSelection(name = "high-itv", strength = 0.6f)),
        lownoiseLoraChain = listOf(LoraSelection(name = "low-itv", strength = 0.4f)),
        loraChain = listOf(LoraSelection(name = "main-itv", strength = 0.5f)),
        capabilities = capabilities,
        isUploading = true,
        uploadProgress = 42,
        isFetching = true
    )

    @Test
    fun `文生视频状态经快照回转后逐字段相等`() {
        val state = fullTtvState()
        val roundTripped = state.withVideoSnapshot(VideoStateSnapshot.of(state))
        // 全字段 equality：of() 或 withVideoSnapshot() 漏掉任何字段都会让回转后的值
        // 退回快照缺省值，equality 立刻破裂
        assertEquals(state, roundTripped)
    }

    @Test
    fun `图生视频状态经快照回转后逐字段相等`() {
        val state = fullItvState()
        val roundTripped = state.withVideoSnapshot(VideoStateSnapshot.of(state))
        assertEquals(state, roundTripped)
    }

    @Test
    fun `快照改写哨兵值后回拷必须全字段生效`() {
        val state = fullTtvState()
        val snapshot = VideoStateSnapshot.of(state)
        val sentinelList = listOf("哨兵")
        val sentinelLora = listOf(LoraSelection(name = "哨兵链", strength = 0.9f))
        val sentinelWorkflows = listOf(
            VideoWorkflowItem(id = "s", name = "s", displayName = "s", type = sh.hnet.comfychair.WorkflowType.TTV)
        )
        snapshot.selectedWorkflow = "哨兵流"
        snapshot.selectedWorkflowId = "哨兵流ID"
        snapshot.availableWorkflows = sentinelWorkflows
        snapshot.workflowPlaceholders = setOf("哨兵占位")
        snapshot.dynamicParams = listOf(dynamicParam)
        snapshot.dynamicParamValues = mapOf("哨兵" to "值")
        snapshot.dynamicParamsRegistryReady = false
        snapshot.selectedCheckpoint = "哨兵ckpt"
        snapshot.selectedUnet = "哨兵unet"
        snapshot.selectedLoraName = "哨兵lora"
        snapshot.loraStrength = 0.1f
        snapshot.selectedHighnoiseUnet = "哨兵h-unet"
        snapshot.selectedLownoiseUnet = "哨兵l-unet"
        snapshot.selectedHighnoiseLora = "哨兵h-lora"
        snapshot.selectedLownoiseLora = "哨兵l-lora"
        snapshot.selectedVae = "哨兵vae"
        snapshot.selectedClip = "哨兵clip"
        snapshot.selectedClip1 = "哨兵clip1"
        snapshot.selectedClip2 = "哨兵clip2"
        snapshot.selectedClip3 = "哨兵clip3"
        snapshot.selectedClip4 = "哨兵clip4"
        snapshot.selectedTextEncoder = "哨兵te"
        snapshot.selectedLatentUpscaleModel = "哨兵lum"
        snapshot.availableCheckpoints = sentinelList
        snapshot.availableUnets = sentinelList
        snapshot.availableLoras = sentinelList
        snapshot.availableVaes = sentinelList
        snapshot.availableClips = sentinelList
        snapshot.availableUpscaleMethods = sentinelList
        snapshot.availableTextEncoders = sentinelList
        snapshot.availableLatentUpscaleModels = sentinelList
        snapshot.availableSamplers = sentinelList
        snapshot.availableSchedulers = sentinelList
        snapshot.availableAspectRatios = sentinelList
        snapshot.availableSpeedModes = sentinelList
        snapshot.filteredCheckpoints = sentinelList
        snapshot.filteredUnets = sentinelList
        snapshot.filteredLoras = sentinelList
        snapshot.filteredVaes = sentinelList
        snapshot.filteredClips = sentinelList
        snapshot.filteredClips1 = sentinelList
        snapshot.filteredClips2 = sentinelList
        snapshot.filteredClips3 = sentinelList
        snapshot.filteredClips4 = sentinelList
        snapshot.filteredTextEncoders = sentinelList
        snapshot.filteredLatentUpscaleModels = sentinelList
        snapshot.width = "1"
        snapshot.height = "2"
        snapshot.megapixels = "3"
        snapshot.length = "4"
        snapshot.fps = "5"
        snapshot.steps = "6"
        snapshot.cfg = "7.0哨兵"
        snapshot.sampler = "哨兵sampler"
        snapshot.scheduler = "哨兵scheduler"
        snapshot.aspectRatio = "哨兵比例"
        snapshot.speedMode = "哨兵速度"
        snapshot.seedMode = SeedMode.FIXED
        snapshot.seed = "哨兵种子"
        snapshot.denoise = "哨兵denoise"
        snapshot.batchSize = "哨兵batch"
        snapshot.upscaleMethod = "哨兵upscale"
        snapshot.scaleBy = "哨兵scale"
        snapshot.stopAtClipLayer = "哨兵clipLayer"
        snapshot.widthError = "哨兵错1"
        snapshot.heightError = null
        snapshot.megapixelsError = "哨兵错3"
        snapshot.lengthError = "哨兵错4"
        snapshot.fpsError = "哨兵错5"
        snapshot.stepsError = "哨兵错6"
        snapshot.cfgError = "哨兵错7"
        snapshot.seedError = "哨兵错8"
        snapshot.denoiseError = "哨兵错9"
        snapshot.batchSizeError = "哨兵错10"
        snapshot.scaleByError = "哨兵错11"
        snapshot.stopAtClipLayerError = "哨兵错12"
        snapshot.positivePrompt = "哨兵正向"
        snapshot.negativePrompt = "哨兵负向"
        snapshot.deferredCheckpoint = "哨兵d1"
        snapshot.deferredUnet = "哨兵d2"
        snapshot.deferredLoraName = "哨兵d3"
        snapshot.deferredHighnoiseUnet = "哨兵d4"
        snapshot.deferredLownoiseUnet = "哨兵d5"
        snapshot.deferredHighnoiseLora = "哨兵d6"
        snapshot.deferredLownoiseLora = "哨兵d7"
        snapshot.deferredVae = "哨兵d8"
        snapshot.deferredClip = "哨兵d9"
        snapshot.deferredClip1 = "哨兵d10"
        snapshot.deferredClip2 = "哨兵d11"
        snapshot.deferredClip3 = "哨兵d12"
        snapshot.deferredClip4 = "哨兵d13"
        snapshot.deferredTextEncoder = "哨兵d14"
        snapshot.deferredLatentUpscaleModel = "哨兵d15"
        snapshot.highnoiseLoraChain = sentinelLora
        snapshot.lownoiseLoraChain = sentinelLora
        snapshot.loraChain = sentinelLora
        snapshot.capabilities = WorkflowCapabilities(hasNegativePrompt = true)
        snapshot.isFetching = false

        val updated = state.withVideoSnapshot(snapshot)

        assertEquals("哨兵流", updated.selectedWorkflow)
        assertEquals("哨兵流ID", updated.selectedWorkflowId)
        assertEquals(sentinelWorkflows, updated.availableWorkflows)
        assertEquals(setOf("哨兵占位"), updated.workflowPlaceholders)
        assertEquals(mapOf("哨兵" to "值"), updated.dynamicParamValues)
        assertEquals(false, updated.dynamicParamsRegistryReady)
        assertEquals("哨兵ckpt", updated.selectedCheckpoint)
        assertEquals("哨兵unet", updated.selectedUnet)
        assertEquals("哨兵lora", updated.selectedLoraName)
        assertEquals(0.1f, updated.loraStrength)
        assertEquals("哨兵h-unet", updated.selectedHighnoiseUnet)
        assertEquals("哨兵l-unet", updated.selectedLownoiseUnet)
        assertEquals("哨兵h-lora", updated.selectedHighnoiseLora)
        assertEquals("哨兵l-lora", updated.selectedLownoiseLora)
        assertEquals("哨兵vae", updated.selectedVae)
        assertEquals("哨兵clip", updated.selectedClip)
        assertEquals("哨兵clip1", updated.selectedClip1)
        assertEquals("哨兵clip2", updated.selectedClip2)
        assertEquals("哨兵clip3", updated.selectedClip3)
        assertEquals("哨兵clip4", updated.selectedClip4)
        assertEquals("哨兵te", updated.selectedTextEncoder)
        assertEquals("哨兵lum", updated.selectedLatentUpscaleModel)
        assertEquals(sentinelList, updated.availableCheckpoints)
        assertEquals(sentinelList, updated.availableUnets)
        assertEquals(sentinelList, updated.availableLoras)
        assertEquals(sentinelList, updated.availableVaes)
        assertEquals(sentinelList, updated.availableClips)
        assertEquals(sentinelList, updated.availableUpscaleMethods)
        assertEquals(sentinelList, updated.availableTextEncoders)
        assertEquals(sentinelList, updated.availableLatentUpscaleModels)
        assertEquals(sentinelList, updated.availableSamplers)
        assertEquals(sentinelList, updated.availableSchedulers)
        assertEquals(sentinelList, updated.availableAspectRatios)
        assertEquals(sentinelList, updated.availableSpeedModes)
        assertEquals(sentinelList, updated.filteredCheckpoints)
        assertEquals(sentinelList, updated.filteredUnets)
        assertEquals(sentinelList, updated.filteredLoras)
        assertEquals(sentinelList, updated.filteredVaes)
        assertEquals(sentinelList, updated.filteredClips)
        assertEquals(sentinelList, updated.filteredClips1)
        assertEquals(sentinelList, updated.filteredClips2)
        assertEquals(sentinelList, updated.filteredClips3)
        assertEquals(sentinelList, updated.filteredClips4)
        assertEquals(sentinelList, updated.filteredTextEncoders)
        assertEquals(sentinelList, updated.filteredLatentUpscaleModels)
        assertEquals("1", updated.width)
        assertEquals("2", updated.height)
        assertEquals("3", updated.megapixels)
        assertEquals("4", updated.length)
        assertEquals("5", updated.fps)
        assertEquals("6", updated.steps)
        assertEquals("7.0哨兵", updated.cfg)
        assertEquals("哨兵sampler", updated.sampler)
        assertEquals("哨兵scheduler", updated.scheduler)
        assertEquals("哨兵比例", updated.aspectRatio)
        assertEquals("哨兵速度", updated.speedMode)
        assertEquals(SeedMode.FIXED, updated.seedMode)
        assertEquals("哨兵种子", updated.seed)
        assertEquals("哨兵denoise", updated.denoise)
        assertEquals("哨兵batch", updated.batchSize)
        assertEquals("哨兵upscale", updated.upscaleMethod)
        assertEquals("哨兵scale", updated.scaleBy)
        assertEquals("哨兵clipLayer", updated.stopAtClipLayer)
        assertEquals("哨兵错1", updated.widthError)
        assertEquals(null, updated.heightError)
        assertEquals("哨兵错3", updated.megapixelsError)
        assertEquals("哨兵错4", updated.lengthError)
        assertEquals("哨兵错5", updated.fpsError)
        assertEquals("哨兵错6", updated.stepsError)
        assertEquals("哨兵错7", updated.cfgError)
        assertEquals("哨兵错8", updated.seedError)
        assertEquals("哨兵错9", updated.denoiseError)
        assertEquals("哨兵错10", updated.batchSizeError)
        assertEquals("哨兵错11", updated.scaleByError)
        assertEquals("哨兵错12", updated.stopAtClipLayerError)
        assertEquals("哨兵正向", updated.positivePrompt)
        assertEquals("哨兵负向", updated.negativePrompt)
        assertEquals("哨兵d1", updated.deferredCheckpoint)
        assertEquals("哨兵d2", updated.deferredUnet)
        assertEquals("哨兵d3", updated.deferredLoraName)
        assertEquals("哨兵d4", updated.deferredHighnoiseUnet)
        assertEquals("哨兵d5", updated.deferredLownoiseUnet)
        assertEquals("哨兵d6", updated.deferredHighnoiseLora)
        assertEquals("哨兵d7", updated.deferredLownoiseLora)
        assertEquals("哨兵d8", updated.deferredVae)
        assertEquals("哨兵d9", updated.deferredClip)
        assertEquals("哨兵d10", updated.deferredClip1)
        assertEquals("哨兵d11", updated.deferredClip2)
        assertEquals("哨兵d12", updated.deferredClip3)
        assertEquals("哨兵d13", updated.deferredClip4)
        assertEquals("哨兵d14", updated.deferredTextEncoder)
        assertEquals("哨兵d15", updated.deferredLatentUpscaleModel)
        assertEquals(sentinelLora, updated.highnoiseLoraChain)
        assertEquals(sentinelLora, updated.lownoiseLoraChain)
        assertEquals(sentinelLora, updated.loraChain)
        assertEquals(WorkflowCapabilities(hasNegativePrompt = true), updated.capabilities)
        assertEquals(false, updated.isFetching)
    }

    @Test
    fun `图生视频独有字段不经快照且回拷后原样保留`() {
        val state = fullItvState()
        val snapshot = VideoStateSnapshot.of(state)
        // 快照上改一个共享字段（模拟共享函数写入）
        snapshot.selectedCheckpoint = "哨兵ckpt"
        val updated = state.withVideoSnapshot(snapshot)

        // 共享字段生效
        assertEquals("哨兵ckpt", updated.selectedCheckpoint)
        // 独有字段原样保留（基类共享写入不得丢 ITV 的图位/音频/上传状态）
        assertEquals(ImageToVideoViewMode.PREVIEW, updated.viewMode)
        assertTrue(updated.slotImages.isEmpty())
        assertEquals(true, updated.legacyTimelineUnsupported)
        assertEquals(true, updated.isUploading)
        assertEquals(42, updated.uploadProgress)
        // 完整性：除被模拟写入的 selectedCheckpoint 外，其余字段与原状态逐字段一致
        assertEquals(state.copy(selectedCheckpoint = "哨兵ckpt"), updated)
    }

    @Test
    fun `快照与原状态是不同实例且回转不共享可变泄漏`() {
        val state = fullTtvState()
        val snapshot = VideoStateSnapshot.of(state)
        // 快照是独立对象：改快照不影响原状态（共享函数的写入语义以此为前提）
        snapshot.seed = "改动后"
        assertNotEquals("改动后", state.seed)
        val updated = state.withVideoSnapshot(snapshot)
        assertEquals("改动后", updated.seed)
        assertEquals("42", state.seed)
    }

    /**
     * Bitmap / Uri 字段在 JVM 单测里造不出实例（android.jar 桩），上面的行为测试盖不住它们。
     * 用源码级断言补位：两侧 withVideoSnapshot 必须逐字段回拷 previewBitmap 与 currentVideoUri
     * （clearPreview / fetchGeneratedVideo 都写它们，漏拷 = 清不掉旧视频 / 取回结果不上屏）。
     * 断言前折叠空白，换行调整不误报。
     */
    @Test
    fun `两侧回拷必须覆盖预览与视频Uri字段`() {
        listOf(
            "src/main/java/sh/hnet/comfychair/viewmodel/TextToVideoViewModel.kt",
            "src/main/java/sh/hnet/comfychair/viewmodel/ImageToVideoViewModel.kt"
        ).forEach { path ->
            val source = File(path).readText()
            val start = source.indexOf("internal fun ")
            assertTrue("找不到 withVideoSnapshot：$path", start >= 0)
            val body = source.substring(source.indexOf("withVideoSnapshot", start))
                .replace(Regex("""\s+"""), " ")
            assertTrue(
                "$path 的回拷必须包含 previewBitmap = snapshot.previewBitmap",
                body.contains("previewBitmap = snapshot.previewBitmap")
            )
            assertTrue(
                "$path 的回拷必须包含 currentVideoUri = snapshot.currentVideoUri",
                body.contains("currentVideoUri = snapshot.currentVideoUri")
            )
        }
    }
}
