package com.autoglm.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoglm.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    onNavigateToLogs: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showApiKey by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.settings_saved)
    
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            snackbarHostState.showSnackbar(savedMessage)
            viewModel.resetSaved()
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Configuration Section
            Text(
                text = stringResource(R.string.settings_api),
                style = MaterialTheme.typography.titleMedium
            )
            
            OutlinedTextField(
                value = uiState.apiUrl,
                onValueChange = viewModel::updateApiUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_api_url)) },
                placeholder = { Text(stringResource(R.string.settings_api_url_hint)) },
                singleLine = true
            )
            
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_api_key)) },
                placeholder = { Text(stringResource(R.string.settings_api_key_hint)) },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null
                        )
                    }
                }
            )
            
            OutlinedTextField(
                value = uiState.modelName,
                onValueChange = viewModel::updateModelName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_model)) },
                placeholder = { Text(stringResource(R.string.settings_model_hint)) },
                singleLine = true
            )
            
            HorizontalDivider()
            
            // Agent Configuration Section
            Text(
                text = stringResource(R.string.settings_agent),
                style = MaterialTheme.typography.titleMedium
            )
            
            OutlinedTextField(
                value = uiState.maxSteps.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { viewModel.updateMaxSteps(it) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_max_steps)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            
            // Language Selection
            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyMedium
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.language == "cn",
                    onClick = { viewModel.updateLanguage("cn") },
                    label = { Text(stringResource(R.string.settings_language_cn)) }
                )
                FilterChip(
                    selected = uiState.language == "en",
                    onClick = { viewModel.updateLanguage("en") },
                    label = { Text(stringResource(R.string.settings_language_en)) }
                )
            }
            
            HorizontalDivider()
            
            // App List Mode
            Text(
                text = stringResource(R.string.settings_app_list_mode),
                style = MaterialTheme.typography.bodyMedium
            )
            
            Text(
                text = stringResource(R.string.settings_app_list_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !uiState.useLargeModelAppList,
                    onClick = { viewModel.updateUseLargeModelAppList(false) },
                    label = { Text(stringResource(R.string.settings_app_list_small)) }
                )
                FilterChip(
                    selected = uiState.useLargeModelAppList,
                    onClick = { viewModel.updateUseLargeModelAppList(true) },
                    label = { Text(stringResource(R.string.settings_app_list_large)) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_save))
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            // Logs Entry
            OutlinedCard(
                onClick = onNavigateToLogs,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "运行日志",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "查看应用运行日志和错误信息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
