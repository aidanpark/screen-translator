package com.galaxy.airviewdictionary.data.local.screen

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Size
import android.view.WindowInsets
import android.view.WindowMetrics
import timber.log.Timber

object ScreenInfoHolder {
    @Volatile
    private var _screenInfo: ScreenInfo = ScreenInfo(
        width = 0,
        height = 0,
        statusBarHeight = 0,
        navBarHeight = 0,
        orientation = 0,
        safePaddingLeft = 0,
        safePaddingTop = 0,
        safePaddingRight = 0,
        safePaddingBottom = 0
    )

    fun set(info: ScreenInfo) { _screenInfo = info }
    fun get(): ScreenInfo = _screenInfo

    fun collectAndStoreScreenInfo(activity: Activity) {
        // 1. 전체 윈도우 크기
        val metrics:Size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = activity.windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            Size(bounds.width(), bounds.height())
        } else {
            val displayMetrics = activity.resources.displayMetrics
            Size(displayMetrics.widthPixels, displayMetrics.heightPixels)
        }

        // 2. 화면 방향
        val orientation = activity.resources.configuration.orientation

        // 크기·방향은 지금 바로 저장한다. 인셋은 뷰가 붙은 뒤에야 알 수 있어 아래 post 에서 채우는데,
        // SettingsActivity 가 곧바로 포인터·메뉴바를 띄우며 크기를 읽으므로 그 사이 0 이 보이면
        // 포인터가 왼쪽 위(−w/2, −h/2)에 반쯤 잘려 뜬다.
        set(get().copy(width = metrics.width, height = metrics.height, orientation = orientation))

        // 3. WindowInsets로 상태바, 네비바, 세이프패딩 구하기
        activity.window.decorView.post {
            val insets = activity.window.decorView.rootWindowInsets

            val statusBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            } else {
                @Suppress("DEPRECATION")
                insets?.systemWindowInsetTop ?: 0
            }

            val navBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
            } else {
                @Suppress("DEPRECATION")
                insets?.systemWindowInsetBottom ?: 0
            }

            val left = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets?.getInsets(WindowInsets.Type.systemBars())?.left ?: 0
            } else {
                0
            }

            val right = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets?.getInsets(WindowInsets.Type.systemBars())?.right ?: 0
            } else {
                0
            }

            val screenInfo = ScreenInfo(
                width = metrics.width,
                height = metrics.height,
                statusBarHeight = statusBarHeight,
                navBarHeight = navBarHeight,
                orientation = orientation,
                safePaddingLeft = left,
                safePaddingTop = statusBarHeight,
                safePaddingRight = right,
                safePaddingBottom = navBarHeight
            )
            Timber.tag("ScreenInfoHolder").d("ScreenInfo $screenInfo")

            set(screenInfo)
        }
    }

    fun updateScreenInfoInService(context: Context) {
        val prevInfo = get()

        val orientation = context.resources.configuration.orientation

        // raw 값
        val w = prevInfo.width
        val h = prevInfo.height

        // orientation에 맞게 width/height 매핑
        val (width, height) = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // 세로: height가 더 커야 함
            if (w < h) w to h else h to w
        } else {
            // 가로: width가 더 커야 함
            if (w > h) w to h else h to w
        }

        val newInfo = ScreenInfo(
            width = width,
            height = height,
            statusBarHeight = prevInfo.statusBarHeight,
            navBarHeight = prevInfo.navBarHeight,
            orientation = orientation,
            safePaddingLeft = prevInfo.safePaddingLeft,
            safePaddingTop = prevInfo.safePaddingTop,
            safePaddingRight = prevInfo.safePaddingRight,
            safePaddingBottom = prevInfo.safePaddingBottom
        )
        Timber.tag("ScreenInfoHolder").d("ScreenInfo newInfo $newInfo")
        set(newInfo)
    }
}