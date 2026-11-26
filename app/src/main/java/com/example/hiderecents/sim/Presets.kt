package com.example.hiderecents.sim

/** 国家码预设。mcc 是该地区的主 MCC，只用于展示和「当前/目标」对照，不写进系统 */
data class CountryPreset(val code: String, val label: String, val mcc: String)

/** 运营商名预设，按国家分组 */
data class CarrierPreset(val label: String, val country: String)

object Presets {

    val countries: List<CountryPreset> = listOf(
        CountryPreset("CN", "中国", "460"),
        CountryPreset("HK", "中国香港", "454"),
        CountryPreset("MO", "中国澳门", "455"),
        CountryPreset("TW", "中国台湾", "466"),
        CountryPreset("JP", "日本", "440"),
        CountryPreset("KR", "韩国", "450"),
        CountryPreset("SG", "新加坡", "525"),
        CountryPreset("MY", "马来西亚", "502"),
        CountryPreset("TH", "泰国", "520"),
        CountryPreset("VN", "越南", "452"),
        CountryPreset("ID", "印度尼西亚", "510"),
        CountryPreset("PH", "菲律宾", "515"),
        CountryPreset("IN", "印度", "404"),
        CountryPreset("AU", "澳大利亚", "505"),
        CountryPreset("NZ", "新西兰", "530"),
        CountryPreset("US", "美国", "310"),
        CountryPreset("CA", "加拿大", "302"),
        CountryPreset("MX", "墨西哥", "334"),
        CountryPreset("BR", "巴西", "724"),
        CountryPreset("AR", "阿根廷", "722"),
        CountryPreset("GB", "英国", "234"),
        CountryPreset("DE", "德国", "262"),
        CountryPreset("FR", "法国", "208"),
        CountryPreset("IT", "意大利", "222"),
        CountryPreset("ES", "西班牙", "214"),
        CountryPreset("PT", "葡萄牙", "268"),
        CountryPreset("RU", "俄罗斯", "250"),
        CountryPreset("ZA", "南非", "655"),
    )

    /** 常用：选择器「常用」分栏用这一组，前五项同时作为主页目标 chips */
    private val commonCodes = listOf("HK", "JP", "US", "SG", "KR", "TW", "GB", "TH", "AU", "MO")

    val common: List<CountryPreset> = commonCodes.mapNotNull { byCode(it) }

    /** 主页目标 chips：与参考板一致的五个 */
    val homeChips: List<CountryPreset> =
        listOf("HK", "JP", "US", "SG", "GB").mapNotNull { byCode(it) }

    val carriers: List<CarrierPreset> = listOf(
        CarrierPreset("China Mobile", "CN"),
        CarrierPreset("China Unicom", "CN"),
        CarrierPreset("China Telecom", "CN"),
        CarrierPreset("CMHK", "HK"),
        CarrierPreset("HKT", "HK"),
        CarrierPreset("3HK", "HK"),
        CarrierPreset("SmarTone", "HK"),
        CarrierPreset("CTM", "MO"),
        CarrierPreset("3 Macau", "MO"),
        CarrierPreset("Chunghwa Telecom", "TW"),
        CarrierPreset("Taiwan Mobile", "TW"),
        CarrierPreset("FarEasTone", "TW"),
        CarrierPreset("NTT docomo", "JP"),
        CarrierPreset("au by KDDI", "JP"),
        CarrierPreset("Softbank", "JP"),
        CarrierPreset("Rakuten Mobile", "JP"),
        CarrierPreset("SK Telecom", "KR"),
        CarrierPreset("KT Corporation", "KR"),
        CarrierPreset("LG U+", "KR"),
        CarrierPreset("Singtel", "SG"),
        CarrierPreset("StarHub", "SG"),
        CarrierPreset("M1", "SG"),
        CarrierPreset("Maxis", "MY"),
        CarrierPreset("Celcom", "MY"),
        CarrierPreset("Digi", "MY"),
        CarrierPreset("U Mobile", "MY"),
        CarrierPreset("AIS", "TH"),
        CarrierPreset("DTAC", "TH"),
        CarrierPreset("True Move H", "TH"),
        CarrierPreset("Viettel Mobile", "VN"),
        CarrierPreset("Vinaphone", "VN"),
        CarrierPreset("Mobifone", "VN"),
        CarrierPreset("Telkomsel", "ID"),
        CarrierPreset("Indosat Ooredoo", "ID"),
        CarrierPreset("XL Axiata", "ID"),
        CarrierPreset("Globe Telecom", "PH"),
        CarrierPreset("Smart Communications", "PH"),
        CarrierPreset("DITO Telecommunity", "PH"),
        CarrierPreset("Reliance Jio", "IN"),
        CarrierPreset("Bharti Airtel", "IN"),
        CarrierPreset("Vodafone Idea", "IN"),
        CarrierPreset("Telstra", "AU"),
        CarrierPreset("Optus", "AU"),
        CarrierPreset("Vodafone AU", "AU"),
        CarrierPreset("Bell Mobility", "CA"),
        CarrierPreset("Rogers Wireless", "CA"),
        CarrierPreset("Telus Mobility", "CA"),
        CarrierPreset("AT&T", "US"),
        CarrierPreset("T-Mobile USA", "US"),
        CarrierPreset("Verizon", "US"),
        CarrierPreset("EE", "GB"),
        CarrierPreset("O2 UK", "GB"),
        CarrierPreset("Three UK", "GB"),
        CarrierPreset("Vodafone UK", "GB"),
        CarrierPreset("T-Mobile DE", "DE"),
        CarrierPreset("Vodafone DE", "DE"),
        CarrierPreset("O2 DE", "DE"),
        CarrierPreset("Orange FR", "FR"),
        CarrierPreset("SFR", "FR"),
        CarrierPreset("Free Mobile", "FR"),
        CarrierPreset("Bouygues Telecom", "FR"),
        CarrierPreset("Telecom Italia", "IT"),
        CarrierPreset("Vodafone IT", "IT"),
        CarrierPreset("Wind Tre", "IT"),
        CarrierPreset("Movistar", "ES"),
        CarrierPreset("Vodafone ES", "ES"),
        CarrierPreset("Orange ES", "ES"),
        CarrierPreset("MTS", "RU"),
        CarrierPreset("MegaFon", "RU"),
        CarrierPreset("Beeline", "RU"),
        CarrierPreset("Vivo", "BR"),
        CarrierPreset("Claro", "BR"),
        CarrierPreset("TIM Brasil", "BR"),
    )

    fun byCode(code: String?): CountryPreset? =
        countries.firstOrNull { it.code.equals(code, ignoreCase = true) }

    fun byMcc(mcc: String?): CountryPreset? =
        mcc?.let { value -> countries.firstOrNull { it.mcc == value } }

    fun carriersOf(country: String): List<CarrierPreset> =
        carriers.filter { it.country.equals(country, ignoreCase = true) }

    /** 列表里显示的代表运营商：取该地区预设的第一个 */
    fun representativeCarrier(country: String): String? =
        carriersOf(country).firstOrNull()?.label
}