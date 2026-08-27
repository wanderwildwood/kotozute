/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either binding.version 3 of the License, or
 * (at your option) any later binding.version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.einkmessaging.feature.settings.about

import android.view.View
import com.jakewharton.rxbinding2.view.clicks
import com.wanderwildwood.einkmessaging.BuildConfig
import com.wanderwildwood.einkmessaging.R
import com.wanderwildwood.einkmessaging.common.base.QkController
import com.wanderwildwood.einkmessaging.common.widget.PreferenceView
import com.wanderwildwood.einkmessaging.injection.appComponent
import io.reactivex.Observable
import javax.inject.Inject
import com.wanderwildwood.einkmessaging.databinding.AboutControllerBinding

class AboutController : QkController<AboutView, Unit, AboutPresenter>(), AboutView {

    private val binding get() = AboutControllerBinding.bind(containerView!!)

    @Inject override lateinit var presenter: AboutPresenter

    init {
        appComponent.inject(this)
        layoutRes = R.layout.about_controller
    }

    override fun onViewCreated() {
        binding.version.summary = BuildConfig.VERSION_NAME
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.about_title)
        showBackButton(true)
    }

    override fun preferenceClicks(): Observable<PreferenceView> = (0 until binding.preferences.childCount)
            .map { index -> binding.preferences.getChildAt(index) }
            .mapNotNull { view -> view as? PreferenceView }
            .map { preference -> preference.clicks().map { preference } }
            .let { preferences -> Observable.merge(preferences) }

    override fun render(state: Unit) {
        // No special rendering required
    }

}