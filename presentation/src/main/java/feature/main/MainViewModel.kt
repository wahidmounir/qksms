/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package feature.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.support.v4.content.ContextCompat
import com.moez.QKSMS.R
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.kotlin.autoDisposable
import common.MenuItem
import common.Navigator
import common.base.QkViewModel
import common.util.filter.ConversationFilter
import injection.appComponent
import interactor.DeleteConversation
import interactor.MarkAllSeen
import interactor.MarkArchived
import interactor.MarkBlocked
import interactor.MarkUnarchived
import interactor.MigratePreferences
import interactor.PartialSync
import io.reactivex.Observable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.withLatestFrom
import io.realm.Realm
import model.SyncLog
import repository.MessageRepository
import util.Preferences
import util.extensions.removeAccents
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class MainViewModel : QkViewModel<MainView, MainState>(MainState()) {

    @Inject lateinit var context: Context
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationFilter: ConversationFilter
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var markAllSeen: MarkAllSeen
    @Inject lateinit var deleteConversation: DeleteConversation
    @Inject lateinit var markArchived: MarkArchived
    @Inject lateinit var markUnarchived: MarkUnarchived
    @Inject lateinit var markBlocked: MarkBlocked
    @Inject lateinit var migratePreferences: MigratePreferences
    @Inject lateinit var partialSync: PartialSync
    @Inject lateinit var prefs: Preferences

    private val menuArchive by lazy { MenuItem(context.getString(R.string.inbox_menu_archive), 0) }
    private val menuUnarchive by lazy { MenuItem(context.getString(R.string.inbox_menu_unarchive), 1) }
    private val menuBlock by lazy { MenuItem(context.getString(R.string.inbox_menu_block), 2) }
    private val menuDelete by lazy { MenuItem(context.getString(R.string.inbox_menu_delete), 3) }

    private val conversations by lazy { messageRepo.getConversations() }

    init {
        appComponent.inject(this)

        // Now the conversations can be loaded
        conversations

        disposables += deleteConversation
        disposables += markAllSeen
        disposables += markArchived
        disposables += markUnarchived
        disposables += migratePreferences
        disposables += partialSync

        // If it's the first sync, reflect that in the ViewState
        disposables += Realm.getDefaultInstance()
                .where(SyncLog::class.java)
                .findAll()
                .asFlowable()
                .filter { it.isLoaded }
                .map { it.size == 0 }
                .doOnNext { if (it) partialSync.execute(Unit) }
                .distinctUntilChanged()
                .subscribe { syncing -> newState { it.copy(syncing = syncing) } }

        // Migrate the preferences from 2.7.3 if necessary
        migratePreferences.execute(Unit)

        val isNotDefaultSms = Telephony.Sms.getDefaultSmsPackage(context) != context.packageName
        val hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasContactPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        if (isNotDefaultSms) {
            partialSync.execute(Unit)
        }

        if (isNotDefaultSms || !hasSmsPermission || !hasContactPermission) {
            navigator.showSetupActivity()
        }

        markAllSeen.execute(Unit)
    }

    override fun bindView(view: MainView) {
        super.bindView(view)

        view.queryChangedIntent
                .debounce(200, TimeUnit.MILLISECONDS)
                .map { query -> query.removeAccents() }
                .withLatestFrom(state, { query, state ->
                    if (state.page is Inbox) {
                        val filteredConversations = if (query.isEmpty()) conversations
                        else conversations
                                .map { conversations -> conversations.filter { conversationFilter.filter(it, query) } }

                        val page = state.page.copy(showClearButton = query.isNotEmpty(), data = filteredConversations)
                        newState { it.copy(page = page) }
                    }
                })
                .autoDisposable(view.scope())
                .subscribe()

        view.queryCancelledIntent
                .autoDisposable(view.scope())
                .subscribe { view.clearSearch() }

        view.composeIntent
                .autoDisposable(view.scope())
                .subscribe { navigator.showCompose() }

        view.drawerOpenIntent
                .autoDisposable(view.scope())
                .subscribe { open -> newState { it.copy(drawerOpen = open) } }

        view.drawerItemIntent
                .doOnNext { newState { it.copy(drawerOpen = false) } }
                .doOnNext { if (it == DrawerItem.SETTINGS) navigator.showSettings() }
                .doOnNext { if (it == DrawerItem.PLUS) navigator.showQksmsPlusActivity() }
                .doOnNext { if (it == DrawerItem.HELP) navigator.showSupport() }
                .distinctUntilChanged()
                .doOnNext {
                    when (it) {
                        DrawerItem.INBOX -> newState { it.copy(page = Inbox()) }
                        DrawerItem.ARCHIVED -> newState { it.copy(page = Archived(messageRepo.getConversations(true))) }
                        DrawerItem.SCHEDULED -> newState { it.copy(page = Scheduled()) }
                        else -> {
                        } // Do nothing
                    }
                }
                .autoDisposable(view.scope())
                .subscribe()

        view.conversationClickIntent
                .doOnNext { view.clearSearch() }
                .doOnNext { threadId -> navigator.showConversation(threadId) }
                .autoDisposable(view.scope())
                .subscribe()

        view.conversationLongClickIntent
                .withLatestFrom(state, { _, mainState ->
                    when (mainState.page) {
                        is Inbox -> {
                            val page = mainState.page.copy(menu = listOf(menuArchive, menuBlock, menuDelete))
                            newState { it.copy(page = page) }
                        }
                        is Archived -> {
                            val page = mainState.page.copy(menu = listOf(menuUnarchive, menuBlock, menuDelete))
                            newState { it.copy(page = page) }
                        }
                    }
                })
                .autoDisposable(view.scope())
                .subscribe()

        view.conversationMenuItemIntent
                .withLatestFrom(state, { actionId, mainState ->
                    when (mainState.page) {
                        is Inbox -> {
                            val page = mainState.page.copy(menu = ArrayList())
                            newState { it.copy(page = page) }
                        }
                        is Archived -> {
                            val page = mainState.page.copy(menu = ArrayList())
                            newState { it.copy(page = page) }
                        }
                    }
                    actionId
                })
                .withLatestFrom(view.conversationLongClickIntent, { actionId, threadId ->
                    when (actionId) {
                        menuArchive.actionId -> markArchived.execute(threadId)
                        menuUnarchive.actionId -> markUnarchived.execute(threadId)
                        menuBlock.actionId -> markBlocked.execute(threadId)
                        menuDelete.actionId -> view.showDeleteDialog()
                    }
                })
                .autoDisposable(view.scope())
                .subscribe()

        // Delete the conversation
        view.confirmDeleteIntent
                .withLatestFrom(view.conversationLongClickIntent, { _, threadId -> threadId })
                .autoDisposable(view.scope())
                .subscribe { threadId -> deleteConversation.execute(threadId) }

        view.swipeConversationIntent
                .withLatestFrom(state, { threadId, state ->
                    markArchived.execute(threadId) {
                        if (state.page is Inbox) {
                            val page = state.page.copy(showArchivedSnackbar = true)
                            newState { it.copy(page = page) }
                        }
                    }
                })
                .switchMap { Observable.timer(2750, TimeUnit.MILLISECONDS) }
                .withLatestFrom(state, { threadId, state ->
                    markArchived.execute(threadId) {
                        if (state.page is Inbox) {
                            val page = state.page.copy(showArchivedSnackbar = false)
                            newState { it.copy(page = page) }
                        }
                    }
                })
                .autoDisposable(view.scope())
                .subscribe()

        view.undoSwipeConversationIntent
                .withLatestFrom(view.swipeConversationIntent, { _, threadId -> threadId })
                .autoDisposable(view.scope())
                .subscribe { threadId -> markUnarchived.execute(threadId) }
    }

}