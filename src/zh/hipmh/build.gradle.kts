import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hipmh"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "嬉皮漫画"
        lang = "zh"
        baseUrl = "https://m.hipmh.com"
    }

    deeplink {
        host("m.hipmh.com")
        host("m.xipmh.com")
        path("/works/..*")
    }
}
