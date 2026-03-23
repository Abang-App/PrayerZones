package com.abang.prayerzones.viewmodel

import androidx.lifecycle.ViewModel
import com.abang.prayerzones.repository.SupportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SupportViewModel @Inject constructor(
    private val supportRepository: SupportRepository
) : ViewModel() {
    val supporters: StateFlow<List<com.abang.prayerzones.model.Supporter>> = supportRepository.supporters

    fun addSupporter(name: String, amount: Int) {
        supportRepository.addSupporter(name = name, amount = amount)
    }
}

