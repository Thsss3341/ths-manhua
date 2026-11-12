import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Yueman"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "漫画大全"
        lang = "zh"
        // The site moves between domains; the current ones are published at http://reman.cc
        baseUrl {
            custom("http://m.yueman1.cc")
        }
    }

    deeplink {
        host("m.yueman1.cc")
        host("www.yueman1.cc")
        path("/manhua/..*")
        path("/p/..*")
    }
}
