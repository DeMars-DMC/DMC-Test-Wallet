package io.demars.stellarwallet.mvvm.exchange

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.demars.stellarwallet.models.ExchangeApiModel
import io.demars.stellarwallet.models.ExchangeMapper
import io.demars.stellarwallet.remote.DmcRetrofit
import io.demars.stellarwallet.remote.ExchangeProvidersApi
import retrofit2.Call
import retrofit2.Callback
import timber.log.Timber

class ExchangeRepository(application: Application) {
    private val appContext = application.applicationContext
    private val exchangeDao: ExchangeDao
    private val listLiveData: MutableLiveData<List<ExchangeEntity>>
    init {
        val exchangeRoomDatabase = ExchangesRoomDatabase.getDatabase(application)
        exchangeDao = exchangeRoomDatabase.exchangeDao()
        listLiveData = MutableLiveData()
    }

    fun getAllExchangeProviders(forceRefresh : Boolean = false) : LiveData<List<ExchangeEntity>> {
        if (forceRefresh || exchangeDao.getAllExchangeProviders().isEmpty()) {
            refreshExchanges()
        } else {
            listLiveData.postValue(exchangeDao.getAllExchangeProviders())
        }
        return listLiveData
    }

    private fun populateExchangeDatabase(exchanges : List<ExchangeApiModel>) {
        val dao = ExchangesRoomDatabase.getDatabase(appContext).exchangeDao()
        dao.deleteAll()
        dao.insertAll(ExchangeMapper.toExchangeEntities(exchanges.toList()))
        Timber.d("Refreshing exchanges database from remote server")
    }

    private fun refreshExchanges() {
        DmcRetrofit.create(ExchangeProvidersApi::class.java).exchangeProviders().enqueue(object : Callback<List<ExchangeApiModel>>{
            override fun onResponse(call: Call<List<ExchangeApiModel>>, response: retrofit2.Response<List<ExchangeApiModel>>) {
                val list = response.body()
                if (list != null && list.isNotEmpty()) {
                    Timber.v("Fetched and updated {${list.size}} exchange providers")
                    populateExchangeDatabase(list.toList())
                    listLiveData.postValue(exchangeDao.getAllExchangeProviders())
                }
            }

            override fun onFailure(call: Call<List<ExchangeApiModel>>, t: Throwable) {
                Timber.e("Error fetching exchange providers")
            }

        })
    }

    fun getExchange(address : String) : ExchangeEntity {
        return exchangeDao.getExchangeProvider(address)
    }
//
//    fun insert(exchange: ExchangeEntity) {
//        exchangeDao.insert(exchange)
//    }
}