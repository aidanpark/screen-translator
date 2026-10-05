// PP-OCRv5 모델(검출기 + 문자권별 인식기와 사전) 을 담는 Play Asset Delivery 팩.
// fast-follow — 앱 설치가 끝나면 Play 가 바로 받아 준다(.docs/vision-engine-design.md §6 결정).
// 모델은 저장소에 없다. tools/paddle/export_models.sh 로 src/main/assets/paddle/ 을 채운다.
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("paddle_models")
    dynamicDelivery {
        deliveryType.set("fast-follow")
    }
}

// 모델 없이 번들을 만들면 PaddleOCR 문자권이 조용히 ML Kit 라틴으로 떨어진다. 번들을 만들 때 막는다.
// script.onnx 는 auto 의 지원되지 않는 문자권 관문(성능 P7) — 직접 학습한 모델이라 저장소(tools/scriptid/model/)에 있고 export_models.sh 가 복사한다.
val requiredModels = listOf("det.onnx", "arabic_rec.onnx", "arabic_dict.txt", "eslav_rec.onnx", "eslav_dict.txt", "th_rec.onnx", "th_dict.txt", "script.onnx")
tasks.configureEach {
    if (name.contains("AssetPack", ignoreCase = true) || name.startsWith("generate")) {
        doFirst {
            val missing = requiredModels.filterNot { file("src/main/assets/paddle/$it").isFile }
            check(missing.isEmpty()) { "PaddleOCR 모델이 없다: $missing — tools/paddle/export_models.sh 로 채운다" }
        }
    }
}
