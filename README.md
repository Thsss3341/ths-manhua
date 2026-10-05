# Ths Manhua

English | [简体中文](README.zh-CN.md)

A [Mihon](https://mihon.app) extension repository for Chinese manga sites. It works in Mihon and in
apps built on it, such as Komikku.

## Contents

- [Installing](#installing)
- [Extensions](#extensions)
  - [古古漫画](#古古漫画)
  - [漫画大全](#漫画大全)
  - [嬉皮漫画](#嬉皮漫画)
  - [漫画柜](#漫画柜)
- [漫画柜 features](#漫画柜-features)
  - [Chapter sections](#chapter-sections)
  - [Chapter order](#chapter-order)
  - [MAL tracking](#mal-tracking)
  - [Importing 我的书架](#importing-我的书架)
- [FAQ](#faq)
- [Credits](#credits)

## Installing

1. In the app, go to **Settings → Browse → Extension repos** (called **Extension stores** in newer
   versions), tap **Add** and paste:

   ```
   https://raw.githubusercontent.com/Thsss3341/ths-manhua/repo/index.pb
   ```

2. Go to **Browse → Extensions** and install the extensions you want. They are marked with the
   **THS** badge.

Updates appear in **Browse → Extensions** like any other extension.

## Extensions

| Extension | Site | Notes |
|-----------|------|-------|
| 古古漫画 | http://www.gugu5.cc | Directory site, reads chapters wherever they are hosted |
| 漫画大全 | http://m.yueman1.cc | Same catalogue as 古古漫画, with a working search |
| 嬉皮漫画 | https://m.hipmh.com | Catalogue mostly from official platforms, no watermarks |
| 漫画柜 (ManHuaGui) | https://www.manhuagui.com | Keiyoushi's 漫画柜 with extra features |

### 古古漫画

A directory site. Depending on the manga, chapters are read from 古古漫画's own reader or from its
partner site 漫画大全; the extension picks the right one for you.

Some manga only link to official sites such as 腾讯动漫 (`ac.qq.com`). These show no chapters, and
their description says so.

### 漫画大全

Shares its catalogue with 古古漫画. The site's own search doesn't work, so the extension searches a
copy of the whole catalogue that is rebuilt every day. Use it to find titles that 古古漫画's search
misses. Pasting a 漫画大全 or 古古漫画 manga link into the search box also works.

The site changes domain from time to time; its current addresses are listed at http://reman.cc. If
it moves, change the base URL in the extension's settings.

### 嬉皮漫画

Its catalogue mostly comes from official platforms such as 快看漫画 and 腾讯动漫, and its images have
no site watermarks. It is **not** more complete than 古古漫画 or 漫画大全, though: for the chapters
checked, it uses the same image sets, including the same missing panels and cut-off pages. Treat it
as a cleaner copy, not a fix for broken chapters.

The site's chapter lists can lag behind by a few hours. The extension also follows each chapter's
"next chapter" link, so new chapters show up before the site's own list catches up; these have no
upload date.

### 漫画柜

Based on [Keiyoushi's](https://github.com/keiyoushi/extensions-source) 漫画柜 extension, with the
extra features described below.

It replaces Keiyoushi's 漫画柜: **uninstall Keiyoushi's version first**, then install this one. Your
library entries carry over. Don't keep both installed.

Settings (Browse → Extensions → 漫画柜 → ⚙):

| Setting | What it does |
|---------|--------------|
| 章节按上传顺序排列 | Lists chapters in upload order (on by default). See [Chapter order](#chapter-order). |
| 标题语言（方便MAL追踪） | Looks up each manga's MyAnimeList entry. See [MAL tracking](#mal-tracking). |
| 显示R18作品 | Shows R18 manga. Restart the app after changing it. |
| 主站每十秒连接数限制 / 图片CDN每秒连接数限制 | Request limits. Lower them if the site starts blocking you. |

## 漫画柜 features

### Chapter sections

漫画柜 splits many manga into sections such as 单话 (chapters), 单行本 (volumes) and 番外篇 (extras).
Each chapter shows its section under its name.

- **Show only one section:** open the manga's filter menu and use **Exclude scanlators** to hide
  the other sections. The section label is stored where the app normally keeps the scanlator.
- **Trackers:** when a manga has a 单话 section, volumes and extras get no chapter number, so
  "第16卷" isn't sent to trackers as chapter 16.

### Chapter order

漫画柜 only gives a date for a manga's newest chapter, so the app can't sort the others by upload
date. Instead, the extension lists chapters newest-uploaded first, across all sections.

- **To read in upload order:** set the chapter sort to **By source**.
- **To group chapters by section as on the website:** turn off **章节按上传顺序排列** in the
  extension settings, then pull down to refresh the manga.

### MAL tracking

MyAnimeList can't find 漫画柜's Chinese titles. Set **标题语言（方便MAL追踪）** in the extension
settings:

| Option | Effect |
|--------|--------|
| 中文（漫画柜原标题） | Default. Nothing changes. |
| 中文，简介里加上MAL ID | Keeps the Chinese title and adds the MAL title and `id:12345` to the top of the description. |
| 罗马音（MAL标题） / 英文 | Also renames the manga to its MAL romaji or English title. The Chinese title stays in the description. |

**Tracking a manga:** open it (or pull down to refresh it), copy `id:12345` from the description,
and paste it into the MAL tracker's search. That gives the exact entry.

**How a match is found:** the extension looks the manga up on [Bangumi](https://bgm.tv) and
[AniList](https://anilist.co). It uses the title, the subtitle, alternative names, the year and the
authors that 漫画柜 lists.

- **Exact match:** the titles are identical.
- **Likely match:** the title is only similar (different translations), but the year or author
  agrees. The ID is followed by **⚠ 非精确匹配，可能不准确**, and the manga is never renamed. Check the
  entry before tracking it.

**When nothing is found:** many Chinese manhua aren't on MAL at all, and these keep their Chinese
title. AniList usually has them, and the app's AniList tracker can find them by their Chinese title.

**Other notes:**
- The first lookup takes a few seconds per manga; after that it is instant. Manga that weren't
  found, or only had a likely match, are looked up again after a week.
- Manga already in your library are only renamed if the app's **Settings → Advanced → Update
  library manga titles to match source** is on. Then pull down to refresh the manga.

### Importing 我的书架

The **我的书架** filter lists the manga on your 漫画柜 bookshelf, so you can add them to your library
in one go. The steps below are for Komikku.

1. **Log in:** open 漫画柜 in **Browse**, tap the WebView (globe) button, log in on the website, then
   go back.
2. **Create a category** for them, e.g. *manhuagui*, under **Settings → Library → Categories**.
3. **Hide manga you already have:** turn on **Settings → Browse → Hide entries already in library**.
4. **Load your bookshelf:** in 漫画柜, open the filter sheet, tick **我的书架** and tap **Filter**.
   - The whole bookshelf loads at once, at about a second per 20 manga, so wait for it to finish.
   - Only manga you haven't added yet will show.
5. **Add them:** tap **Bulk selection mode** (or long-press a manga), then **Select all**, then
   **Add to library**.
   - If a title is already in your library from another site, Komikku asks about duplicates;
     choose **Skip all**.
   - Then pick the category.

If adding a very long list gets stuck, add it in smaller batches.

## FAQ

**Installing or updating an extension always asks to "scan the app".**
That's Google Play Protect, which checks every newly installed app. To stop it, go to
**Settings → Browse → Installer**, choose **Private**, then uninstall and reinstall the
extensions. They then live inside the app and Android no longer sees them as separate apps.

**A chapter has missing panels or cut-off pages.**
The free sources (古古漫画, 漫画大全, 嬉皮漫画, and others such as mh160) mostly share the same image
sets, so a broken chapter is usually broken everywhere. The complete version is normally only on the
official platform, often as a paid chapter.

**Searching 古古漫画 doesn't find a manga.**
Search in 漫画大全 instead: it has the same catalogue and a full search.

## Credits

The 漫画柜 extension and the build tooling are adapted from
[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source). The whole repository
is under the [Apache License 2.0](LICENSE).

This project is not affiliated with Mihon, Komikku or any of the sites above.
