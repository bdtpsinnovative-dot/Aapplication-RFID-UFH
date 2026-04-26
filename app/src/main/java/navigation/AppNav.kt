package navigation
import androidx.compose.material.icons.filled.Logout
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ui.Other1Screen
import ui.Other2Screen
import data.AppError
import data.AuthManager
import data.ProductSyncManager
import data.SessionEventBus
import data.SessionStore
import data.SupabaseAuthApi
import kotlinx.coroutines.launch
import ui.CheckRfidScreen
import ui.CompareStockScreenV2
import ui.MoreArrangeScreen
import ui.MoreDamageScreen
import ui.MoreInitialCountScreen
import ui.MoreIssuesScreen
import ui.MoreTransferScreen
import ui.MoreUpdateSystemScreen
import ui.LotCheckScreen
import ui.LotMenuScreen
import ui.LotSelectScreen
import ui.ReceiveScreen
import ui.StockCountScreen

// --- Data Class สำหรับเมนู ---
data class MenuData(
    val title: String,
    val icon: ImageVector,
    val route: String,
    val color: Color
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val navScope = rememberCoroutineScope()

    // auto-logout เมื่อ token ใช้งานไม่ได้ (หมดอายุ + refresh ไม่ผ่าน)
    LaunchedEffect(Unit) {
        SessionEventBus.sessionExpired.collect {
            SessionStore.clear(ctx)
            nav.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // ✅ กันกดกลับรัวๆ (ไม่ให้ pop ซ้อน)
    var backLocked by remember { mutableStateOf(false) }

    // ปลดล็อกทุกครั้งที่ปลายทางเปลี่ยน
    DisposableEffect(nav) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ ->
            backLocked = false
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }

    // ✅ ใช้สำหรับ “กด 2 ครั้งเพื่อออก”
    var lastBackAt by remember { mutableStateOf(0L) }
    fun toastOrExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt <= 2000L) {
            (ctx as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(ctx, "กดอีกครั้งเพื่อออก", Toast.LENGTH_SHORT).show()
        }
    }

    // ✅ back แบบปลอดภัย: ถ้าไม่มีหน้าก่อนหน้าแล้ว “ห้าม pop จนว่าง”
    fun safeBack(): Boolean {
        if (backLocked) return true
        backLocked = true

        val hasPrev = nav.previousBackStackEntry != null
        if (!hasPrev) {
            backLocked = false
            // อยู่ root แล้ว → ไม่ pop ให้สแตกว่างเด็ดขาด
            toastOrExit()
            return false
        }

        val popped = nav.popBackStack()
        if (!popped) backLocked = false
        return popped
    }

    // ✅ ดักปุ่ม Back ของเครื่อง (สำคัญมาก)
    val backEntry by nav.currentBackStackEntryAsState()
    val routeNow = backEntry?.destination?.route

    BackHandler {
        when (routeNow) {
            Routes.MENU -> {
                // หน้าเมนู: กด 2 ครั้งเพื่อออก
                toastOrExit()
            }
            Routes.LOGIN, Routes.SPLASH, null -> {
                // login/splash: กดกลับให้ออกแอพไปเลย (กันขาว)
                (ctx as? Activity)?.finish()
            }
            else -> {
                safeBack()
            }
        }
    }

    NavHost(navController = nav, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            SplashGate(
                onGoLogin = {
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onGoMenu = {
                    nav.navigate(Routes.MENU) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(onSuccess = {
                // sync product cache ทุกครั้งที่ login (ข้อมูลสินค้าใหม่เสมอ)
                navScope.launch {
                    try {
                        val tok = AuthManager.getValidAccessToken(ctx)
                        if (tok != null) ProductSyncManager.syncAll(ctx, tok)
                    } catch (_: Exception) {}
                }
                nav.navigate(Routes.MENU) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }

        composable(Routes.MENU) {
            MenuScreen(
                onGo = { route -> nav.navigate(route) },
                onLogout = {
                    SessionStore.clear(ctx)
                    nav.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MENU) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        // ✅ ทุกหน้าที่มีปุ่มกลับ ให้ใช้ safeBack() แทน popBackStack()
        composable(Routes.LOT_SELECT) {
            LotSelectScreen(
                onBack = { safeBack() },
                onNoLot = { nav.navigate(Routes.RECEIVE) },
                onSelectLot = { lotId, lotCode ->
                    nav.navigate(Routes.lotMenu(lotId, lotCode))
                }
            )
        }
        composable(Routes.LOT_MENU) { back ->
            val lotId   = back.arguments?.getString("lotId")?.toLongOrNull() ?: 0L
            val lotCode = java.net.URLDecoder.decode(back.arguments?.getString("lotCode") ?: "", "UTF-8")
            LotMenuScreen(
                lotId     = lotId,
                lotCode   = lotCode,
                onBack    = { safeBack() },
                onReceive = { nav.navigate(Routes.lotReceive(lotId, lotCode)) },
                onCheck   = { nav.navigate(Routes.lotCheck(lotId, lotCode)) }
            )
        }
        composable(Routes.LOT_RECEIVE) { back ->
            val lotId   = back.arguments?.getString("lotId")?.toLongOrNull() ?: 0L
            val lotCode = java.net.URLDecoder.decode(back.arguments?.getString("lotCode") ?: "", "UTF-8")
            ReceiveScreen(
                onBack  = { safeBack() },
                lotId   = lotId,
                lotCode = lotCode
            )
        }
        composable(Routes.LOT_CHECK) { back ->
            val lotId   = back.arguments?.getString("lotId")?.toLongOrNull() ?: 0L
            val lotCode = java.net.URLDecoder.decode(back.arguments?.getString("lotCode") ?: "", "UTF-8")
            LotCheckScreen(
                lotId   = lotId,
                lotCode = lotCode,
                onBack  = { safeBack() },
                onDone  = {
                    nav.navigate(Routes.LOT_SELECT) {
                        popUpTo(Routes.LOT_SELECT) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.RECEIVE) { ReceiveScreen(onBack = { safeBack() }) }
        composable(Routes.CHECK_RFID) { CheckRfidScreen(onBack = { safeBack() }) }
        composable(Routes.STOCK_COUNT) { StockCountScreen(onBack = { safeBack() }) }

        // ถ้าหน้าคุณเป็น CompareScreen wrapper อยู่แล้วก็ใช้แบบนี้
        composable(Routes.COMPARE) { CompareScreen(onBack = { safeBack() }) }

        composable(Routes.OTHER1) { Other1Screen(onBack = { safeBack() }) }
        composable(Routes.OTHER2) {
            Other2Screen(
                onBack = { safeBack() },
                onGo = { route -> nav.navigate(route) }
            )
        }

        // ✅ 6 หน้าย่อย MORE_* (คุณบอกว่ามีครบ)
        composable(Routes.MORE_TRANSFER) { MoreTransferScreen(onBack = { safeBack() }) }
        composable(Routes.MORE_ARRANGE) { MoreArrangeScreen(onBack = { safeBack() }) }
        composable(Routes.MORE_INITIAL_COUNT) { MoreInitialCountScreen(onBack = { safeBack() }) }
        composable(Routes.MORE_DAMAGE) { MoreDamageScreen(onBack = { safeBack() }) }
        composable(Routes.MORE_ISSUES) { MoreIssuesScreen(onBack = { safeBack() }) }
        composable(Routes.MORE_UPDATE_SYSTEM) { MoreUpdateSystemScreen(onBack = { safeBack() }) }
    }
}

@Composable
fun CompareScreen(onBack: () -> Boolean) {
    CompareStockScreenV2(onBack = { onBack() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(onSuccess: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val bgBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFE3F2FD), Color(0xFFFFFFFF))
    )

    Scaffold { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .background(bgBrush)
                .padding(pad)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 400.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Inventory2,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "EOB_RFID",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "จัดการสต๊อกหลังร้านนับสินค้า",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )

                Spacer(Modifier.height(32.dp))

                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("อีเมล") },
                            leadingIcon = { Icon(Icons.Filled.Email, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("รหัสผ่าน") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        if (!msg.isNullOrBlank()) {
                            Text(
                                text = msg!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Button(
                            onClick = {
                                val e = email.trim()
                                val p = pass.trim()
                                if (e.isBlank() || p.isBlank()) {
                                    msg = "กรุณากรอกข้อมูลให้ครบถ้วน"
                                    return@Button
                                }
                                scope.launch {
                                    loading = true
                                    msg = null
                                    try {
                                        // 1. Login
                                        val r = SupabaseAuthApi.signIn(e, p)

                                        // 2. ดึง Profile (ซึ่งตอนนี้จะ Join เอา branchName มาให้ด้วย)
                                        val profile = SupabaseAuthApi.fetchProfile(r.userId, r.accessToken)

                                        // 3. บันทึกทุกอย่างลง Session
                                        SessionStore.save(
                                            ctx = ctx,
                                            accessToken = r.accessToken,
                                            refreshToken = r.refreshToken,
                                            userId = r.userId,
                                            email = r.email,
                                            displayName = profile.fullName.ifBlank { e.substringBefore("@") },
                                            role = profile.role,
                                            branchId = profile.branchId,
                                            branchName = profile.branchName // ✅ ส่งชื่อสาขาไปบันทึก
                                        )
                                        onSuccess()
                                    } catch (ex: Exception) {
                                        msg = AppError.resolve(ex)
                                    } finally {
                                        loading = false
                                    }
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White
                                )
                            } else {
                                Text("เข้าสู่ระบบ", fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SplashGate(
    onGoLogin: () -> Unit,
    onGoMenu: () -> Unit
) {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        val validToken = AuthManager.getValidAccessToken(ctx)
        if (validToken.isNullOrBlank()) {
            SessionStore.clear(ctx)
            onGoLogin()
        } else {
            // sync product cache ถ้าว่างหรือเก่า (background — ไม่บล็อก UI)
            if (ProductSyncManager.needsSync(ctx)) {
                try { ProductSyncManager.syncAll(ctx, validToken) } catch (_: Exception) {}
            }
            onGoMenu()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("กำลังตรวจสอบบัญชี…")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuScreen(
    onGo: (String) -> Unit,
    onLogout: () -> Unit
) {
    val ctx = LocalContext.current

    // ✅ ดึงข้อมูลมาแสดงผล
    val displayName = remember { SessionStore.getDisplayName(ctx) ?: "พนักงาน" }
    val role = remember { SessionStore.getRole(ctx) ?: "Staff" }
    val branchName = remember { SessionStore.getBranchName(ctx) ?: "สาขาทั่วไป" }

    // ✅ สร้างรายการเมนู (แก้ Unresolved reference 'menuItems')
    val menuItems = listOf(
        MenuData("รับสินค้า", Icons.Outlined.Input, Routes.LOT_SELECT, Color(0xFF4CAF50)),
        MenuData("เช็ค RFID", Icons.Outlined.QrCodeScanner, Routes.CHECK_RFID, Color(0xFF2196F3)),
        MenuData("นับสต็อก", Icons.Outlined.Inventory, Routes.STOCK_COUNT, Color(0xFFFF9800)),
        MenuData("เปรียบเทียบ", Icons.Outlined.CompareArrows, Routes.COMPARE, Color(0xFF9C27B0)),
        MenuData("เมนูอื่นๆ 1", Icons.Outlined.MoreHoriz, Routes.OTHER1, Color(0xFF607D8B)),
        MenuData("ตั้งค่า", Icons.Outlined.Settings, Routes.OTHER2, Color(0xFF795548))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("เมนูหลัก") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, contentDescription = "ออกจากระบบ")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {

            // ✅ ส่วน Header แสดงข้อมูลผู้ใช้
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // รูปโปรไฟล์ (วงกลมตัวอักษรแรก)
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = displayName.take(1).uppercase(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))

                    // ข้อมูลตัวหนังสือ
                    Column {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))

                        // แถวแสดง [ตำแหน่ง] และ [สาขา]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Badge ตำแหน่ง
                            Surface(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = role.uppercase(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // Badge สาขา (มีไอคอนร้านค้า)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Store,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = branchName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }
            } // จบ Box Header (แก้ปีกกาหายตรงนี้)

            Text(
                "เลือกรายการทำงาน",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(menuItems) { item ->
                    DashboardCard(item) { onGo(item.route) }
                }
            }
        }
    }
}

// ✅ ย้าย DashboardCard มาไว้นอกสุด (แก้ Unresolved reference)
@Composable
fun DashboardCard(item: MenuData, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.height(140.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(item.color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = item.color
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.DarkGray
            )
        }
    }
}