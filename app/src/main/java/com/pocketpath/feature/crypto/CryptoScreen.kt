package com.pocketpath.feature.crypto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketpath.domain.model.TransactionKind
import com.pocketpath.domain.money.KnownLkrAmount
import com.pocketpath.feature.finance.FinanceEvent
import com.pocketpath.feature.finance.FinanceEvent.EditCryptoTrade
import com.pocketpath.feature.finance.FinanceUiState
import com.pocketpath.ui.components.FinanceBody
import com.pocketpath.ui.components.FinanceChoice
import com.pocketpath.ui.components.FinanceMessage
import com.pocketpath.ui.components.lkrText

@Composable
fun CryptoScreen(state: FinanceUiState, onEvent: (FinanceEvent) -> Unit) {
    FinanceBody(state, onEvent) {
        val crypto = state.crypto
        val fresh = !crypto.loading && !crypto.cached && crypto.positionsError == null
        val editable = state.ready && !crypto.hasUnresolvedWrite
        Text("Crypto holdings", style = MaterialTheme.typography.headlineSmall)
        Text("Record buys and sells you made elsewhere. Use an LKR account and enter trades in date order.")
        if (crypto.loading) Text("Loading holdings…")
        if (crypto.cached) FinanceMessage("Showing cached holdings. Connect to record a new trade.", error = false)
        crypto.positionsError?.let { FinanceMessage(it) }
        crypto.error?.let { FinanceMessage(it) }
        crypto.message?.let { FinanceMessage(it, error = false) }

        for (wallet in state.cryptoWallets) {
            val position = crypto.positions[wallet.id]
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(wallet.name, style = MaterialTheme.typography.titleMedium)
                    when {
                        position != null -> {
                            Text("${position.quantity.major().stripTrailingZeros().toPlainString()} ${wallet.asset.code}")
                            val cost = position.cost as? KnownLkrAmount.Known
                            Text(cost?.let { "Cost basis: ${lkrText(it.amount.minor)}" } ?: "Cost basis unavailable")
                        }
                        fresh -> {
                            Text("0 ${wallet.asset.code}")
                            Text("Cost basis: ${lkrText(0)}")
                        }
                        else -> Text("Holding unavailable")
                    }
                }
            }
        }

        if (crypto.retryWallet) {
            Button(onClick = { onEvent(FinanceEvent.CreateCryptoWallet) }, enabled = !crypto.busy && state.ready) {
                Text("Retry wallet creation")
            }
        } else if (state.availableCryptoAssets.isNotEmpty()) {
            val asset = state.availableCryptoAssets.firstOrNull { it.code == crypto.assetCode }
                ?: state.availableCryptoAssets.first()
            FinanceChoice("New wallet asset", asset.code, state.availableCryptoAssets.map { it.code to it.code }, editable) {
                onEvent(FinanceEvent.SelectCryptoAsset(it))
            }
            Button(onClick = { onEvent(FinanceEvent.CreateCryptoWallet) }, enabled = editable) {
                Text("Create empty wallet")
            }
        }

        if (crypto.retryTrade) {
            Text("The trade may have reached the server. Retry will check the original trade.")
            Button(onClick = { onEvent(FinanceEvent.SaveCryptoTrade) }, enabled = !crypto.busy && state.ready) {
                Text("Retry trade")
            }
        } else if (state.cryptoWallets.isNotEmpty()) {
            CryptoTradeFields(state, onEvent, editable && fresh)
        }
        if (crypto.busy) Text("Saving…")
    }
}

@Composable
private fun CryptoTradeFields(state: FinanceUiState, onEvent: (FinanceEvent) -> Unit, enabled: Boolean) {
    val form = state.crypto.trade
    val wallet = state.selectedCryptoWallet
    val quote = state.selectedCryptoQuoteAccount
    val sell = form.kind == TransactionKind.CRYPTO_SELL
    val canEnter = enabled && quote != null
    Text("Record a trade", style = MaterialTheme.typography.titleLarge)
    if (quote == null) Text("Create an LKR account before recording a trade.")
    FinanceChoice(
        "Trade", form.kind.name,
        listOf(TransactionKind.CRYPTO_BUY.name to "Buy", TransactionKind.CRYPTO_SELL.name to "Sell"),
        canEnter,
    ) { onEvent(EditCryptoTrade(kind = TransactionKind.valueOf(it))) }
    FinanceChoice("Wallet", wallet?.id, state.cryptoWallets.map { it.id to it.name }, canEnter) {
        onEvent(EditCryptoTrade(walletId = it))
    }
    FinanceChoice(
        if (sell) "Receive into" else "Pay from",
        quote?.id, state.selectableAccounts.map { it.id to it.name }, canEnter,
    ) { onEvent(EditCryptoTrade(quoteAccountId = it)) }
    CryptoTradeField("Quantity (${wallet?.asset?.code.orEmpty()})", form.quantity, canEnter) {
        onEvent(EditCryptoTrade(quantity = it))
    }
    CryptoTradeField("Trade value before fees (LKR)", form.gross, canEnter) {
        onEvent(EditCryptoTrade(gross = it))
    }
    CryptoTradeField("Fee (LKR, optional)", form.fee, canEnter) { onEvent(EditCryptoTrade(fee = it)) }
    OutlinedTextField(
        value = form.date, onValueChange = { onEvent(EditCryptoTrade(date = it)) },
        label = { Text("Date (YYYY-MM-DD, blank for today)") },
        enabled = canEnter, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onEvent(FinanceEvent.SaveCryptoTrade) },
        enabled = canEnter && form.quantity.isNotBlank() && form.gross.isNotBlank(),
    ) { Text(if (sell) "Record sell" else "Record buy") }
}

@Composable
private fun CryptoTradeField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
