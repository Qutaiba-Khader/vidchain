package app.ui.main.fragments.browser.suggestions

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class SearchSuggestion(
    @SerializedName("qry") val query: String? = null,
    @SerializedName("gprid") val groupId: String? = null,
    @SerializedName("results") val results: List<SuggestionResult>? = null
)

data class SuggestionResult(
    @SerializedName("key") val key: String? = null,
    @SerializedName("mrk") val mark: Int? = null
)

data class SearchResponse(
    @SerializedName("gossip") val searchSuggestion: SearchSuggestion? = null
)

interface SuggestionApiService {
    @GET("sg/")
    fun getSearchSuggestions(
        @Query("output") format: String = "json",
        @Query("nresults") resultCount: Int,
        @Query("command") query: String
    ): Call<SearchResponse>
}

object RetrofitClient {
    private const val BASE_URL = "https://sugg.search.yahoo.net/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(client)
        .build()

    val api: SuggestionApiService = retrofit.create(SuggestionApiService::class.java)
}

fun fetchSearchSuggestions(
    query: String,
    numberOfResults: Int,
    callback: (List<String>) -> Unit
) {
    RetrofitClient.api.getSearchSuggestions(resultCount = numberOfResults, query = query)
        .enqueue(object : Callback<SearchResponse> {
            override fun onResponse(call: Call<SearchResponse>, response: Response<SearchResponse>) {
                val suggestions = if (response.isSuccessful) {
                    response.body()?.searchSuggestion?.results?.mapNotNull { it.key }
                } else emptyList()
                callback(suggestions ?: emptyList())
            }

            override fun onFailure(call: Call<SearchResponse>, error: Throwable) {
                error.printStackTrace()
                callback(emptyList())
            }
        })
}
