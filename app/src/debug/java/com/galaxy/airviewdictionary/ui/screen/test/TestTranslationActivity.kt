package com.galaxy.airviewdictionary.ui.screen.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.ui.screen.AVDActivity
import com.galaxy.airviewdictionary.ui.theme.ScreenTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

const val TAG = "TranslationScreen"

@AndroidEntryPoint
class TestTranslationActivity : AVDActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ScreenTranslatorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    TestTranslation()
                }
            }
        }
    }
}

@Composable
fun TestTranslation(translationViewModel: TestTranslationViewModel = hiltViewModel()) {

    val translationState by translationViewModel.translationFlow.collectAsStateWithLifecycle()

    TestTranslationScreen(
        resultText = translationState.resultText,
        requestTranslate = { translationViewModel.request(it) })
}

@Composable
fun TestTranslationScreen(
    resultText: String?,
    requestTranslate: (String) -> Unit,
) {
    val mediumPadding = 16.dp
    var userInput by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var debounceJob by rememberSaveable { mutableStateOf<Job?>(null) }

    Column(
        modifier = Modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .safeDrawingPadding()
            .padding(mediumPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
        )

        TranslationLayout(
            onUserInputChanged = {
                userInput = it
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(200) // Debounce delay
                    Timber.tag(TAG).d("requestTranslate %s", userInput)
                    requestTranslate(userInput)
                }
            },
            userInput = userInput,
            onKeyboardDone = {},
            translation = resultText ?: "",
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(mediumPadding)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(mediumPadding),
            verticalArrangement = Arrangement.spacedBy(mediumPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedButton(
                onClick = { userInput = "" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Clear",
                    fontSize = 16.sp
                )
            }
        }
    }
}


@Composable
fun TranslationLayout(
    translation: String,
    userInput: String,
    onUserInputChanged: (String) -> Unit,
    onKeyboardDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mediumPadding = 16.dp

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(mediumPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(mediumPadding)
        ) {
            Text(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceTint)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .align(alignment = Alignment.End),
                text = "Kor -> Eng",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = userInput,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                onValueChange = onUserInputChanged,
                label = { Text("enter your word") },
                keyboardOptions = KeyboardOptions.Default.copy(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onKeyboardDone() }
                )
            )
        }
    }
}


//@Preview(showBackground = true, showSystemUi = true)
//@Composable
//fun GameScreenPreview() {
//    ScreenTranslatorTheme {
//        TestTranslationScreen(
//            response = Response().apply {
//                transText = "히히히히"
//            },
//            requestTranslate = {
//                Timber.tag(TAG).d("requestTranslate: %s", it)
//            })
//    }
//}
