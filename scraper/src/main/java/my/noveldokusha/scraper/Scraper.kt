package my.noveldokusha.scraper

import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.databases.BakaUpdates
import my.noveldokusha.scraper.databases.NovelUpdates
import my.noveldokusha.scraper.sources.AT
import my.noveldokusha.scraper.sources.BacaLightnovel
import my.noveldokusha.scraper.sources.BoxNovel
import my.noveldokusha.scraper.sources.IndoWebnovel
import my.noveldokusha.scraper.sources.LocalSource
import my.noveldokusha.scraper.sources.MeioNovel
import my.noveldokusha.scraper.sources.MoreNovel
import my.noveldokusha.scraper.sources.NovelBin
import my.noveldokusha.scraper.sources.NovelHall
import my.noveldokusha.scraper.sources.Novelku
import my.noveldokusha.scraper.sources.ReadNovelFull
import my.noveldokusha.scraper.sources.Reddit
import my.noveldokusha.scraper.sources.RoyalRoad
import my.noveldokusha.scraper.sources.Saikai
import my.noveldokusha.scraper.sources.SakuraNovel
import my.noveldokusha.scraper.sources.Sousetsuka
import my.noveldokusha.scraper.sources.WbNovel
import my.noveldokusha.scraper.sources.WuxiaWorld
import my.noveldokusha.scraper.sources.ScribbleHub
import my.noveldokusha.scraper.sources.FreeWebNovel
import my.noveldokusha.scraper.sources.NovelFull
import my.noveldokusha.scraper.sources.AllNovel
import my.noveldokusha.scraper.sources.NovelBinCom
import my.noveldokusha.scraper.sources.ReadMTL
import my.noveldokusha.scraper.sources.NewNovel
import my.noveldokusha.scraper.sources.SonicMTL
import my.noveldokusha.scraper.sources.NoBadNovel
import my.noveldokusha.scraper.sources.FanMTL
import my.noveldokusha.scraper.sources.LNMTL
import my.noveldokusha.scraper.sources.WtrLab
import my.noveldokusha.scraper.sources.Shuba69
import my.noveldokusha.scraper.sources.UuKanshu
import my.noveldokusha.scraper.sources.Ddxss
import my.noveldokusha.scraper.sources.LeYueDu
import my.noveldokusha.scraper.sources.Twkan
import my.noveldokusha.scraper.sources.Ttkan
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Scraper @Inject constructor(
    networkClient: NetworkClient,
    localSource: LocalSource
) {
    val databasesList = setOf(
        NovelUpdates(networkClient),
        BakaUpdates(networkClient)
    )

    val sourcesList = setOf(
        localSource,
        ReadNovelFull(networkClient),
        RoyalRoad(networkClient),
        my.noveldokusha.scraper.sources.NovelUpdates(networkClient),
        Reddit(),
        AT(),
        Sousetsuka(),
        Saikai(networkClient),
        BoxNovel(networkClient),
        NovelHall(networkClient),
        WuxiaWorld(networkClient),
        IndoWebnovel(networkClient),
        Shuba69(networkClient),
        UuKanshu(networkClient),
        Ddxss(networkClient),
        LeYueDu(networkClient),
        Twkan(networkClient),
        Ttkan(networkClient),
        BacaLightnovel(networkClient),
        SakuraNovel(networkClient),
        MeioNovel(networkClient),
        MoreNovel(networkClient),
        Novelku(networkClient),
        WbNovel(networkClient),
        NovelBin(networkClient),
        ScribbleHub(networkClient),
        FreeWebNovel(networkClient),
        NovelFull(networkClient),
        AllNovel(networkClient),
        NovelBinCom(networkClient),
        ReadMTL(networkClient),
        NewNovel(networkClient),
        SonicMTL(networkClient),
        NoBadNovel(networkClient),
        FanMTL(networkClient),
        LNMTL(networkClient),
        WtrLab(networkClient),
    )

    val sourcesCatalogsList = sourcesList.filterIsInstance<SourceInterface.Catalog>()
    val sourcesCatalogsLanguagesList = sourcesCatalogsList.mapNotNull { it.language }.toSet()

    private fun String.isCompatibleWithBaseUrl(baseUrl: String): Boolean {
        val normalizedUrl = if (this.endsWith("/")) this else "$this/"
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return normalizedUrl.startsWith(normalizedBaseUrl)
    }

    fun getCompatibleSource(url: String): SourceInterface? =
        sourcesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }

    fun getCompatibleSourceCatalog(url: String): SourceInterface.Catalog? =
        sourcesCatalogsList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }

    fun getCompatibleDatabase(url: String): DatabaseInterface? =
        databasesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }
}
