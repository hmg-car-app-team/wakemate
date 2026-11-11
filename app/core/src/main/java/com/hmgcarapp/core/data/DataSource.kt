package com.hmgcarapp.core.data

import com.hmgcarapp.core.log.HmgLog

/**
 * Repository(Fake/Remote) 패턴 가이드
 * ------------------------------------
 * 1) 앱별 도메인 인터페이스를 만든다:  interface FuelPriceRepository { suspend fun nearby(lat: Double, lon: Double): List<Station> }
 * 2) FakeFuelPriceRepository(고정 샘플) / RemoteFuelPriceRepository(HTTPS API, apiKey 필요) 를 구현한다.
 * 3) 선택은 [DataSources.select] 로 한다 - apiKey 가 비어 있으면 자동으로 Fake.
 *      val repo = DataSources.select("fuelwise", BuildConfig.API_KEY, fake = FakeFuelRepo()) { key -> RemoteFuelRepo(key) }
 * 4) **API 키는 코드/Git 에 절대 넣지 않는다.** 각 앱 모듈의 build.gradle.kts 가 아래 순서로 읽어 BuildConfig.API_KEY 로 주입한다:
 *      local.properties 의 `<앱>.apiKey=...` (예: fuelwise.apiKey)  ->  환경변수 `<APP>_API_KEY` (예: FUELWISE_API_KEY)  ->  빈 문자열
 *    local.properties 는 apps/.gitignore 에 포함돼 있다. 통신은 HTTPS 만.
 * 5) 어떤 소스가 선택됐는지 화면/로그에 표시한다([DataSourceInfo]) - 심사자에게 "가짜 데이터" 를 숨기지 않는다.
 */
enum class DataSourceKind { FAKE, REMOTE }

data class DataSourceInfo(val kind: DataSourceKind, val label: String)

/** 선택된 구현체와 그 종류. */
data class Selected<R>(val repo: R, val info: DataSourceInfo)

object DataSources {
    /** apiKey 가 비어 있으면(공백 포함) Fake, 아니면 [remote] 로 Remote 생성. Remote 생성 예외 시에도 Fake 로 폴백. */
    fun <R> select(appName: String, apiKey: String?, fake: R, remote: (apiKey: String) -> R): Selected<R> {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty()) {
            HmgLog.i("DataSource", "$appName: API 키 없음 -> Fake 사용")
            return Selected(fake, DataSourceInfo(DataSourceKind.FAKE, "샘플 데이터"))
        }
        return try {
            Selected(remote(key), DataSourceInfo(DataSourceKind.REMOTE, "실시간 데이터"))
        } catch (t: Throwable) {
            HmgLog.w("DataSource", "$appName: Remote 생성 실패 -> Fake 폴백", t)
            Selected(fake, DataSourceInfo(DataSourceKind.FAKE, "샘플 데이터(원격 실패)"))
        }
    }
}
