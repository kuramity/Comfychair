package sh.hnet.comfychair.viewmodel.base

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.hnet.comfychair.taglibrary.state.PromptBridge
import sh.hnet.comfychair.viewmodel.ImageToVideoViewModel
import sh.hnet.comfychair.viewmodel.TextToVideoViewModel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 钉住「子类构造不得在属性初始化完成前同步读 uiState」的 init 顺序约束。
 *
 * 背景：B4.2-B 曾把 PromptBridge.register（内含两个 `uiState.map{}.stateIn(Eagerly, "")`）
 * 从子类 init 上提到父类 init。父类 init 先于子类属性初始化执行，而 viewModelScope 用
 * `Dispatchers.Main.immediate`——主线程上 Eagerly 的 sharing 协程**同步内联执行**：
 * collect uiState → 触发 uiState by-lazy → 读到子类尚未初始化的 `initialState`（null）
 * → map transform 对 null receiver NPE（2026-11-28 模拟器冷启动实抓，主线程崩溃，
 * 崩溃栈：MainContainerActivity.onCreate → BaseVideoGenerationViewModel :433）。
 *
 * 修复（方案 G）：实现留父类（registerToPromptBridge / startCoreObservations），调用放回
 * 两个子类 init 末尾。同族路径一并钉住：父类 init 的三条观察 launch 在 Main.immediate
 * 下同样是同步内联执行（首个发射立刻跑），曾读到未初始化的 logTag / _uiState。
 *
 * 本测试的复现原理：`Dispatchers.setMain(UnconfinedTestDispatcher())` 让 Main.immediate
 * 在 JVM 单测里也真跑且内联执行——与生产冷启动路径一致（此前 JVM 单测全绿的原因是
 * 没有 kotlinx-coroutines-test，Main 未解析、launch 体根本不执行）。
 *
 * 断言分四层，保证异常无论走「构造器抛出」还是「协程未捕获异常通道」都会变红：
 * 1. 构造不抛异常；
 * 2. 构造期间没有协程未捕获异常（默认 UncaughtExceptionHandler 捕获）；
 * 3. `uiState.value` 必须是真实的初始状态（带 bug 时 _uiState lazy 塞进 null，
 *    读 selectedWorkflowId 直接 NPE——这是与异常路由无关的直读证据）；
 * 4. PromptBridge 已注册（targetOf 查询面）。
 */
class BaseVideoGenerationInitOrderTest {

    /** 构造期间经协程未捕获异常通道上报的异常（生产上是主线程崩溃）。 */
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        // 让 Dispatchers.Main.immediate 在 JVM 测试里真跑且内联执行（复现冷启动同步路径）
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // PromptBridge 是 JVM 单例，注销防止串扰同 JVM 里其他测试（P2-20 注册表语义）
        PromptBridge.unregister(TextToVideoViewModel.OWNER_ID)
        PromptBridge.unregister(ImageToVideoViewModel.OWNER_ID)
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
    }

    /** 构造完成后的公共断言（上文四层里的 1/2/3/4）。 */
    private fun assertHealthyConstruction(
        ownerId: String,
        construct: () -> BaseVideoGenerationViewModel<*, *>
    ) {
        val vm = try {
            construct()
        } catch (t: Throwable) {
            throw AssertionError(
                "ViewModel 构造抛异常（疑似 init 顺序回归：子类属性初始化完成前同步读了 uiState）", t
            )
        }
        assertTrue(
            "构造期间出现协程未捕获异常（生产上即主线程崩溃）：$uncaught",
            uncaught.isEmpty()
        )
        // 直读证据：带 bug 时 uiState.value 是 null，这里直接 NPE
        assertEquals(
            "uiState 必须已是真实初始状态（selectedWorkflowId 合法空串）",
            "",
            vm.uiState.value.selectedWorkflowId
        )
        assertNotNull(
            "构造完成后必须已注册到标签库桥接（ownerId=$ownerId）",
            PromptBridge.targetOf(ownerId)
        )
    }

    @Test
    fun `文生视频构造不得在属性初始化完成前同步读 uiState`() {
        assertHealthyConstruction(TextToVideoViewModel.OWNER_ID) { TextToVideoViewModel() }
    }

    @Test
    fun `图生视频构造不得在属性初始化完成前同步读 uiState`() {
        assertHealthyConstruction(ImageToVideoViewModel.OWNER_ID) { ImageToVideoViewModel() }
    }
}
