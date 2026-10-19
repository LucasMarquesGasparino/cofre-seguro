package com.cofreseguro;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

public class MainActivity extends android.app.Activity {
    private static final int REQUEST_CREDENTIAL = 4101;
    private static final int REQUEST_BACKUP_EXPORT = 4102;
    private static final int REQUEST_RESTORE = 4103;
    private static final int REQUEST_CSV = 4104;
    private static final int MAX_BACKUP_BYTES = 20 * 1024 * 1024;
    private static final int MAX_CSV_BYTES = 5 * 1024 * 1024;
    private static final int SORT_ALPHABETICAL = 0;
    private static final int SORT_ACCESS = 1;
    private static final int SORT_DUPLICATES = 2;
    private static final int SORT_UPDATED = 3;
    private static final int BIOMETRIC_ERROR_LOCKOUT = 7;
    private static final int BIOMETRIC_ERROR_HW_UNAVAILABLE = 1;
    private static final int BIOMETRIC_ERROR_NO_BIOMETRICS = 11;
    private static final int BIOMETRIC_ERROR_HW_NOT_PRESENT = 12;
    private static final int BIOMETRIC_ERROR_LOCKOUT_PERMANENT = 9;
    private static final int BIOMETRIC_ERROR_NEGATIVE_BUTTON = 13;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Dialog> openDialogs = new ArrayList<>();
    private VaultStore store;
    private KeyguardManager keyguardManager;
    private CancellationSignal cancelSignal;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String pendingExportText;
    private boolean recoveryMode;
    private LinearLayout reminderBand;
    private TextView reminderText;
    private Button lockedSecondaryButton;
    private Button lockedTertiaryButton;

    private FrameLayout root;
    private ScrollView scrollView;
    private LinearLayout content;
    private LinearLayout listContainer;
    private Spinner sortSpinner;
    private TextView countLabel;
    private LinearLayout lockedOverlay;
    private TextView lockedTitle;
    private TextView lockedMessage;
    private Button lockedButton;
    private Button visibilityButton;
    private EditText searchField;

    private boolean unlocked;
    private boolean authInProgress;
    private boolean filePickerOpen;
    private boolean uiReady;
    private boolean passwordsVisible;
    private boolean resettingSearch;
    private boolean autoPromptAllowed = true;
    private int sortMode = SORT_ALPHABETICAL;
    private long backgroundedAt;
    private String clipboardText;
    private BroadcastReceiver screenOffReceiver;

    private static final long IDLE_LOCK_MS = 5 * 60 * 1000L;
    private static final long FILE_PICKER_LOCK_MS = 5 * 60 * 1000L;
    private static final long CLIPBOARD_CLEAR_MS = 30_000L;

    private final Runnable lockRunnable = new Runnable() {
        @Override
        public void run() {
            lockAndHide();
        }
    };
    private final Runnable idleLockRunnable = new Runnable() {
        @Override
        public void run() {
            lockAndHide();
        }
    };
    private final Runnable clearClipRunnable = new Runnable() {
        @Override
        public void run() {
            clearClipboardNow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                lockAndHide();
            }
        };
        IntentFilter screenOff = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, screenOff, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, screenOff);
        }
        store = new VaultStore(this);
        buildUi();
        uiReady = true;
        showLocked("Bolsa bloqueada", "Desbloqueie com a biometria ou com o bloqueio de tela do aparelho.", false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!authInProgress && !filePickerOpen) {
            autoPromptAllowed = true;
        }
        if (!unlocked) {
            return;
        }
        if (filePickerOpen) {
            handler.postDelayed(lockRunnable, FILE_PICKER_LOCK_MS);
        } else if (lockTimeout() == 0) {
            lockAndHide();
        } else {
            backgroundedAt = SystemClock.elapsedRealtime();
            handler.postDelayed(lockRunnable, lockTimeout());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(lockRunnable);
        if (unlocked && !filePickerOpen && backgroundedAt > 0
                && SystemClock.elapsedRealtime() - backgroundedAt > lockTimeout()) {
            lockAndHide();
        }
        backgroundedAt = 0;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        scheduleIdleLock();
    }

    private void scheduleIdleLock() {
        handler.removeCallbacks(idleLockRunnable);
        if (unlocked) {
            handler.postDelayed(idleLockRunnable, IDLE_LOCK_MS);
        }
    }

    private long lockTimeout() {
        return prefs.getLong("lockTimeoutMs", 30000);
    }

    private void showSettings() {
        final long[] values = {0, 30000, 60000, 300000};
        final String[] labels = {"Imediatamente", "Após 30 segundos", "Após 1 minuto", "Após 5 minutos"};
        LinearLayout body = vertical(6);
        RadioGroup group = new RadioGroup(this);
        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < values.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setText(labels[i]);
            option.setTextColor(Color.rgb(18, 32, 54));
            option.setId(View.generateViewId());
            option.setChecked(values[i] == lockTimeout());
            final long value = values[i];
            option.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    prefs.edit().putLong("lockTimeoutMs", value).apply();
                    holder[0].dismiss();
                }
            });
            group.addView(option, new RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, dp(48)));
        }
        body.addView(group);
        body.addView(text("A bolsa também bloqueia ao desligar a tela e após 5 minutos sem uso.",
                13, Color.rgb(91, 105, 126)), marginParams(0, 10, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Bloquear ao sair do app")
                .setView(body)
                .setNegativeButton("Fechar", null)
                .create();
        holder[0] = dialog;
        secure(dialog);
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uiReady && !unlocked && !authInProgress && autoPromptAllowed && !recoveryMode) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestUnlock();
                }
            }, 250);
        }
    }

    @Override
    protected void onDestroy() {
        if (cancelSignal != null) {
            cancelSignal.cancel();
            cancelSignal = null;
        }
        clearClipboardNow();
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (IllegalArgumentException error) {
                // Já desregistrado.
            }
            screenOffReceiver = null;
        }
        if (store != null) {
            store.clearMemory();
        }
        super.onDestroy();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(magicBackground());
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        content = vertical(20);
        content.setPadding(dp(20), dp(22), dp(20), dp(28));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = horizontal(0);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView sigil = text("✦", 25, Color.rgb(255, 215, 112));
        sigil.setGravity(Gravity.CENTER);
        header.addView(sigil, new LinearLayout.LayoutParams(dp(30), -2));
        LinearLayout titleBlock = vertical(0);
        TextView title = text("Bolsa da Hermione", 27, Color.rgb(255, 244, 211));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleBlock.addView(title);
        TextView subtitle = text("Suas credenciais, protegidas no aparelho", 14, Color.rgb(214, 204, 241));
        titleBlock.addView(subtitle, marginParams(0, 4, 0, 0));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));

        visibilityButton = button("\uD83D\uDC41", Color.rgb(55, 40, 92), Color.rgb(255, 215, 112));
        visibilityButton.setTextSize(20);
        visibilityButton.setContentDescription("Mostrar senhas");
        visibilityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                passwordsVisible = !passwordsVisible;
                visibilityButton.setContentDescription(passwordsVisible ? "Ocultar senhas" : "Mostrar senhas");
                visibilityButton.setBackground(borderBackground(
                        passwordsVisible ? Color.rgb(105, 72, 151) : Color.rgb(55, 40, 92),
                        passwordsVisible ? Color.rgb(255, 215, 112) : Color.rgb(105, 72, 151), 12));
                renderVault();
            }
        });
        header.addView(visibilityButton, new LinearLayout.LayoutParams(dp(48), dp(42)));
        Button lockButton = button("🔒", Color.rgb(55, 40, 92), Color.rgb(255, 215, 112));
        lockButton.setTextSize(18);
        lockButton.setContentDescription("Bloquear agora");
        lockButton.setBackground(borderBackground(Color.rgb(55, 40, 92), Color.rgb(105, 82, 151), 12));
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                lockAndHide();
            }
        });
        header.addView(lockButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        LinearLayout actionRow = horizontal(8);
        Button addButton = button("+ Nova senha", Color.rgb(111, 62, 186), Color.WHITE);
        addButton.setBackground(borderBackground(Color.rgb(111, 62, 186), Color.rgb(206, 158, 255), 12));
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showEntryEditor(null);
            }
        });
        actionRow.addView(addButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button backupButton = button("Backup", Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        backupButton.setBackground(borderBackground(Color.rgb(49, 39, 83), Color.rgb(105, 82, 151), 12));
        backupButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showBackupMenu();
            }
        });
        actionRow.addView(backupButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button settingsButton = button("Ajustes", Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        settingsButton.setBackground(borderBackground(Color.rgb(49, 39, 83), Color.rgb(105, 82, 151), 12));
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showSettings();
            }
        });
        actionRow.addView(settingsButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(actionRow, marginParams(0, 22, 0, 0));

        reminderBand = vertical(8);
        reminderBand.setBackground(borderBackground(Color.rgb(58, 40, 98), Color.rgb(255, 215, 112), 12));
        reminderBand.setPadding(dp(14), dp(12), dp(14), dp(12));
        reminderText = text("", 14, Color.rgb(255, 244, 211));
        reminderBand.addView(reminderText);
        Button reminderAction = button("Fazer backup agora", Color.rgb(111, 62, 186), Color.WHITE);
        reminderAction.setBackground(borderBackground(Color.rgb(111, 62, 186), Color.rgb(206, 158, 255), 12));
        reminderAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showExportDialog();
            }
        });
        reminderBand.addView(reminderAction, marginParams(0, 8, 0, 0));
        reminderBand.setVisibility(View.GONE);
        content.addView(reminderBand, marginParams(0, 14, 0, 0));

        LinearLayout listHeader = horizontal(0);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        countLabel = text("0 registros", 18, Color.rgb(255, 231, 165));
        countLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        listHeader.addView(countLabel, new LinearLayout.LayoutParams(0, -2, 1));

        sortSpinner = new Spinner(this);
        String[] sorts = new String[]{"Ordem alfabética", "Mais acessadas", "Senhas iguais", "Mais recentes"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, sorts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(adapter);
        sortSpinner.setBackground(borderBackground(Color.rgb(239, 235, 255), Color.rgb(171, 133, 229), 12));
        sortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sortMode = position;
                if (unlocked) {
                    renderVault();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        listHeader.addView(sortSpinner, new LinearLayout.LayoutParams(dp(170), dp(48)));
        content.addView(listHeader, marginParams(0, 25, 0, 0));

        searchField = editField("Buscar site, usuário ou senha", "", false);
        searchField.setTextColor(Color.rgb(35, 25, 65));
        searchField.setHintTextColor(Color.rgb(112, 93, 147));
        searchField.setBackground(borderBackground(Color.rgb(244, 241, 255), Color.rgb(171, 133, 229), 12));
        searchField.setContentDescription("Buscar por site, usuário ou senha");
        searchField.setVisibility(View.GONE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (unlocked && !resettingSearch) {
                    renderVault();
                }
            }
        });
        content.addView(searchField, marginParams(0, 12, 0, 0));

        listContainer = vertical(10);
        content.addView(listContainer, marginParams(0, 10, 0, 0));

        lockedOverlay = vertical(20);
        lockedOverlay.setGravity(Gravity.CENTER);
        lockedOverlay.setPadding(dp(34), dp(30), dp(34), dp(30));
        lockedOverlay.setBackground(magicBackground());
        lockedOverlay.setClickable(true);
        lockedOverlay.setFocusable(true);
        ImageView lockIcon = new ImageView(this);
        lockIcon.setImageResource(R.drawable.ic_magic_bag);
        lockIcon.setContentDescription(null);
        lockedOverlay.addView(lockIcon, new LinearLayout.LayoutParams(dp(96), dp(96)));
        lockedTitle = text("Bolsa bloqueada", 25, Color.rgb(255, 244, 211));
        lockedTitle.setGravity(Gravity.CENTER);
        lockedTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lockedOverlay.addView(lockedTitle);
        lockedMessage = text("", 15, Color.rgb(214, 204, 241));
        lockedMessage.setGravity(Gravity.CENTER);
        lockedOverlay.addView(lockedMessage, marginParams(0, 12, 0, 0));
        lockedButton = button("Desbloquear", Color.rgb(111, 62, 186), Color.WHITE);
        lockedButton.setBackground(borderBackground(Color.rgb(111, 62, 186), Color.rgb(206, 158, 255), 12));
        lockedOverlay.addView(lockedButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        lockedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestUnlock();
            }
        });
        lockedSecondaryButton = button("", Color.rgb(55, 40, 92), Color.rgb(255, 215, 112));
        lockedSecondaryButton.setBackground(borderBackground(Color.rgb(55, 40, 92), Color.rgb(105, 82, 151), 12));
        lockedSecondaryButton.setVisibility(View.GONE);
        lockedOverlay.addView(lockedSecondaryButton, marginedFullWidth(10, 50));
        lockedTertiaryButton = button("", Color.rgb(55, 40, 92), Color.rgb(232, 222, 255));
        lockedTertiaryButton.setBackground(borderBackground(Color.rgb(55, 40, 92), Color.rgb(105, 82, 151), 12));
        lockedTertiaryButton.setVisibility(View.GONE);
        lockedOverlay.addView(lockedTertiaryButton, marginedFullWidth(10, 50));
        root.addView(lockedOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void requestUnlock() {
        if (!uiReady || unlocked || authInProgress) {
            return;
        }
        if (keyguardManager == null || !keyguardManager.isKeyguardSecure()) {
            showSecuritySetup();
            return;
        }

        authInProgress = true;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Executor executor = getMainExecutor();
                cancelSignal = new CancellationSignal();
                BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                        .setTitle("Desbloquear Bolsa da Hermione")
                        .setSubtitle("Confirme sua identidade para ver as credenciais")
                        .setDescription("A chave do cofre permanece protegida pelo Android");
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else if (Build.VERSION.SDK_INT == 29) {
                    builder.setDeviceCredentialAllowed(true);
                } else {
                    builder.setNegativeButton("Usar PIN ou padrão", executor,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    launchDeviceCredential();
                                }
                            });
                }
                BiometricPrompt prompt = builder.build();
                prompt.authenticate(cancelSignal, executor, new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        unlockAndLoad();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        if (errorCode == BIOMETRIC_ERROR_NEGATIVE_BUTTON) {
                            return;
                        }
                        authInProgress = false;
                        cancelSignal = null;
                        if (errorCode == BIOMETRIC_ERROR_HW_UNAVAILABLE
                                || errorCode == BIOMETRIC_ERROR_NO_BIOMETRICS
                                || errorCode == BIOMETRIC_ERROR_HW_NOT_PRESENT
                                || errorCode == BIOMETRIC_ERROR_LOCKOUT
                                || errorCode == BIOMETRIC_ERROR_LOCKOUT_PERMANENT) {
                            launchDeviceCredential();
                        } else {
                            autoPromptAllowed = false;
                            showLocked("Bolsa bloqueada", "Toque em Desbloquear para tentar de novo.", false);
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        Toast.makeText(MainActivity.this, "Biometria não reconhecida", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (RuntimeException error) {
                cancelSignal = null;
                launchDeviceCredential();
            }
        } else {
            launchDeviceCredential();
        }
    }

    private void launchDeviceCredential() {
        Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(
                "Desbloquear Bolsa da Hermione", "Confirme o bloqueio de tela do aparelho");
        if (intent == null) {
            authInProgress = false;
            showSecuritySetup();
            return;
        }
        authInProgress = true;
        startActivityForResult(intent, REQUEST_CREDENTIAL);
    }

    private void unlockAndLoad() {
        authInProgress = false;
        cancelSignal = null;
        try {
            store.load();
        } catch (Exception error) {
            unlocked = false;
            recoveryMode = true;
            showRecovery();
            return;
        }
        recoveryMode = false;
        finishUnlock();
        if (store.consumeRecoveredFlag()) {
            showMessage("Bolsa recuperada",
                    "O arquivo principal estava danificado; a cópia anterior foi recuperada.");
        }
    }

    private void finishUnlock() {
        unlocked = true;
        authInProgress = false;
        scrollView.setVisibility(View.VISIBLE);
        lockedOverlay.setVisibility(View.GONE);
        sortSpinner.setEnabled(true);
        renderVault();
        scheduleIdleLock();
    }

    private void showRecovery() {
        showLocked("Não foi possível abrir a bolsa",
                "O arquivo pode estar danificado. Tente de novo, restaure um backup ou comece uma bolsa nova. "
                        + "O arquivo ilegível nunca é apagado.", false);
        lockedButton.setText("Tentar de novo");
        lockedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                recoveryMode = false;
                unlockAndLoad();
            }
        });
        lockedSecondaryButton.setText("Restaurar de um backup");
        lockedSecondaryButton.setVisibility(View.VISIBLE);
        lockedSecondaryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startRestore();
            }
        });
        lockedTertiaryButton.setText("Começar bolsa nova");
        lockedTertiaryButton.setVisibility(View.VISIBLE);
        lockedTertiaryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmFreshStart();
            }
        });
    }

    private void lockAndHide() {
        handler.removeCallbacks(lockRunnable);
        handler.removeCallbacks(idleLockRunnable);
        dismissAllDialogs();
        if (!unlocked) {
            return;
        }
        unlocked = false;
        passwordsVisible = false;
        if (searchField != null) {
            resettingSearch = true;
            searchField.setText("");
            resettingSearch = false;
        }
        if (store != null) {
            store.clearMemory();
        }
        if (listContainer != null) {
            listContainer.removeAllViews();
        }
        if (sortSpinner != null) {
            sortSpinner.setEnabled(false);
        }
        if (lockedOverlay != null) {
            showLocked("Bolsa bloqueada", "Desbloqueie com a biometria ou com o bloqueio de tela do aparelho.", false);
        }
    }

    private void showSecuritySetup() {
        showLocked("Ative o bloqueio do aparelho",
                "A Bolsa da Hermione exige PIN, padrão ou senha do Android para proteger suas credenciais.", true);
    }

    private void showLocked(String title, String message, boolean openSettings) {
        if (lockedOverlay == null) {
            return;
        }
        lockedTitle.setText(title);
        lockedMessage.setText(message);
        lockedButton.setText(openSettings ? "Abrir configurações de segurança" : "Desbloquear");
        lockedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (openSettings) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    } catch (RuntimeException error) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                } else {
                    requestUnlock();
                }
            }
        });
        if (lockedSecondaryButton != null) {
            lockedSecondaryButton.setVisibility(View.GONE);
            lockedTertiaryButton.setVisibility(View.GONE);
        }
        scrollView.setVisibility(View.INVISIBLE);
        lockedOverlay.setVisibility(View.VISIBLE);
    }

    private boolean vaultReady() {
        return unlocked && store != null && store.isLoaded();
    }

    private void secure(final Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            window.getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        openDialogs.add(dialog);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dismissed) {
                openDialogs.remove(dialog);
            }
        });
    }

    private void dismissAllDialogs() {
        for (Dialog dialog : new ArrayList<>(openDialogs)) {
            dialog.dismiss();
        }
        openDialogs.clear();
    }

    private void showMessage(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .create();
        secure(dialog);
        dialog.show();
    }

    private void renderVault() {
        if (!unlocked) {
            return;
        }
        listContainer.removeAllViews();
        updateReminder();
        ArrayList<VaultEntry> allEntries = new ArrayList<>(store.getEntries());
        updateSearchVisibility(allEntries.size());
        String query = searchField == null ? "" : searchField.getText().toString().trim();
        ArrayList<VaultEntry> entries = filterEntries(allEntries, query);
        if (query.length() == 0) {
            countLabel.setText(allEntries.size() + (allEntries.size() == 1 ? " registro" : " registros"));
        } else {
            countLabel.setText(entries.size() + " de " + allEntries.size() + " registros");
        }
        if (entries.isEmpty()) {
            if (query.length() == 0) {
                renderEmptyState();
            } else {
                TextView empty = text("Nenhum registro combina com essa busca.", 16, Color.rgb(220, 211, 242));
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(12), dp(44), dp(12), dp(44));
                listContainer.addView(empty, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, -2));
            }
            return;
        }

        if (sortMode == SORT_DUPLICATES) {
            renderGrouped(entries);
        } else {
            if (sortMode == SORT_ACCESS) {
                Collections.sort(entries, new Comparator<VaultEntry>() {
                    @Override
                    public int compare(VaultEntry left, VaultEntry right) {
                        int count = Integer.compare(right.accessCount, left.accessCount);
                        return count != 0 ? count : left.site.compareToIgnoreCase(right.site);
                    }
                });
            } else if (sortMode == SORT_UPDATED) {
                Collections.sort(entries, new Comparator<VaultEntry>() {
                    @Override
                    public int compare(VaultEntry left, VaultEntry right) {
                        int updated = Long.compare(right.updatedAt, left.updatedAt);
                        return updated != 0 ? updated : left.site.compareToIgnoreCase(right.site);
                    }
                });
            } else {
                Collections.sort(entries, new Comparator<VaultEntry>() {
                    @Override
                    public int compare(VaultEntry left, VaultEntry right) {
                        return left.site.compareToIgnoreCase(right.site);
                    }
                });
            }
            for (VaultEntry entry : entries) {
                listContainer.addView(entryCard(entry));
            }
        }
    }

    private void updateSearchVisibility(int totalEntries) {
        if (searchField == null) {
            return;
        }
        if (totalEntries > 6) {
            searchField.setVisibility(View.VISIBLE);
        } else {
            if (searchField.getText().length() > 0) {
                resettingSearch = true;
                searchField.setText("");
                resettingSearch = false;
            }
            searchField.setVisibility(View.GONE);
        }
    }

    private ArrayList<VaultEntry> filterEntries(ArrayList<VaultEntry> source, String query) {
        ArrayList<VaultEntry> filtered = new ArrayList<>();
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        if (normalizedQuery.length() == 0) {
            filtered.addAll(source);
            return filtered;
        }
        for (VaultEntry entry : source) {
            if (contains(entry.site, normalizedQuery)
                    || contains(entry.username, normalizedQuery)
                    || contains(entry.password, normalizedQuery)) {
                filtered.add(entry);
            }
        }
        return filtered;
    }

    private boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private void renderGrouped(ArrayList<VaultEntry> entries) {
        LinkedHashMap<String, ArrayList<VaultEntry>> grouped = new LinkedHashMap<>();
        for (VaultEntry entry : entries) {
            String key = entry.password;
            ArrayList<VaultEntry> group = grouped.get(key);
            if (group == null) {
                group = new ArrayList<>();
                grouped.put(key, group);
            }
            group.add(entry);
        }
        ArrayList<ArrayList<VaultEntry>> groups = new ArrayList<>(grouped.values());
        for (ArrayList<VaultEntry> group : groups) {
            Collections.sort(group, new Comparator<VaultEntry>() {
                @Override
                public int compare(VaultEntry left, VaultEntry right) {
                    return left.site.compareToIgnoreCase(right.site);
                }
            });
        }
        Collections.sort(groups, new Comparator<ArrayList<VaultEntry>>() {
            @Override
            public int compare(ArrayList<VaultEntry> left, ArrayList<VaultEntry> right) {
                int size = Integer.compare(right.size(), left.size());
                return size != 0 ? size : left.get(0).site.compareToIgnoreCase(right.get(0).site);
            }
        });
        for (ArrayList<VaultEntry> group : groups) {
            String groupTitle = group.size() == 1
                    ? "Senha usada em 1 registro"
                    : "Senha usada em " + group.size() + " registros";
            if (group.get(0).password.length() == 0) {
                groupTitle = "Sem senha cadastrada";
            }
            TextView heading = text(groupTitle, 14, Color.rgb(238, 204, 119));
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            listContainer.addView(heading, marginParams(4, 12, 4, 0));
            for (VaultEntry entry : group) {
                listContainer.addView(entryCard(entry));
            }
        }
    }

    private View entryCard(final VaultEntry entry) {
        LinearLayout card = horizontal(14);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(8), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(235, 31, 24, 62));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(91, 71, 132));
        card.setBackground(background);
        card.setElevation(dp(2));

        LinearLayout information = vertical(0);
        TextView site = text(entry.site, 17, Color.rgb(255, 241, 188));
        site.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        information.addView(site);
        String secondary = entry.username.length() == 0 ? "Usuário não informado" : entry.username;
        TextView user = text(secondary, 14, Color.rgb(218, 209, 241));
        information.addView(user, marginParams(0, 4, 0, 0));
        TextView accesses = text("Acessos: " + entry.accessCount, 12, Color.rgb(174, 154, 211));
        information.addView(accesses, marginParams(0, 4, 0, 0));
        TextView date = text(dateLabel(entry), 12, Color.rgb(174, 154, 211));
        information.addView(date, marginParams(0, 4, 0, 0));
        if (passwordsVisible) {
            String passwordLabel = entry.password.length() == 0 ? "Senha não informada" : "Senha: " + entry.password;
            TextView password = text(passwordLabel, 13, Color.rgb(245, 204, 111));
            password.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            password.setSingleLine(true);
            password.setEllipsize(android.text.TextUtils.TruncateAt.END);
            information.addView(password, marginParams(0, 4, 0, 0));
        }
        card.addView(information, new LinearLayout.LayoutParams(0, -2, 1));

        TextView arrow = text("›", 30, Color.rgb(255, 207, 101));
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(60)));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openEntry(entry);
            }
        });
        return card;
    }

    private void openEntry(final VaultEntry entry) {
        if (!vaultReady()) {
            return;
        }
        try {
            store.recordAccess(entry);
        } catch (Exception error) {
            showError("Não foi possível registrar o acesso", error);
        }

        final LinearLayout body = vertical(8);
        TextView info = text("Toque em Mostrar para revelar a senha. O conteúdo fica visível somente enquanto o cofre está desbloqueado.",
                13, Color.rgb(91, 105, 126));
        body.addView(info);
        body.addView(text("Usuário", 12, Color.rgb(121, 137, 160)), marginParams(0, 14, 0, 0));
        TextView username = text(entry.username.length() == 0 ? "Não informado" : entry.username,
                17, Color.rgb(18, 32, 54));
        body.addView(username);
        body.addView(text(dateLabel(entry), 12, Color.rgb(121, 105, 153)), marginParams(0, 6, 0, 0));
        Button copyUsername = button("Copiar usuário", Color.rgb(231, 238, 250), Color.rgb(39, 82, 147));
        copyUsername.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyFromEntry("Usuário", entry, entry.username);
            }
        });
        body.addView(copyUsername, marginParams(0, 6, 0, 0));

        body.addView(text("Senha", 12, Color.rgb(121, 137, 160)), marginParams(0, 16, 0, 0));
        final boolean[] visible = new boolean[]{passwordsVisible};
        final TextView password = text(visible[0]
                        ? (entry.password.length() == 0 ? "Não informada" : entry.password)
                        : mask(entry.password),
                18, Color.rgb(18, 32, 54));
        password.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        body.addView(password);
        LinearLayout passwordActions = horizontal(8);
        final Button reveal = button("Mostrar", Color.rgb(231, 238, 250), Color.rgb(39, 82, 147));
        reveal.setText(visible[0] ? "Ocultar" : "Mostrar");
        reveal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                visible[0] = !visible[0];
                password.setText(visible[0] ? (entry.password.length() == 0 ? "Não informada" : entry.password) : mask(entry.password));
                reveal.setText(visible[0] ? "Ocultar" : "Mostrar");
            }
        });
        passwordActions.addView(reveal, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button copyPassword = button("Copiar senha", Color.rgb(39, 110, 241), Color.WHITE);
        copyPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyFromEntry("Senha", entry, entry.password);
            }
        });
        passwordActions.addView(copyPassword, new LinearLayout.LayoutParams(0, dp(46), 1));
        body.addView(passwordActions, marginParams(0, 6, 0, 0));

        Button edit = button("Editar registro", Color.WHITE, Color.rgb(39, 82, 147));
        edit.setBackground(borderBackground(Color.WHITE, Color.rgb(39, 110, 241), 12));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showEntryEditor(entry);
            }
        });
        body.addView(edit, marginParams(0, 18, 0, 0));
        final AlertDialog[] entryDialog = new AlertDialog[1];
        Button delete = button("Apagar registro", Color.rgb(255, 235, 235), Color.rgb(179, 48, 48));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDelete(entry, entryDialog[0]);
            }
        });
        body.addView(delete);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(entry.site)
                .setView(body)
                .setNegativeButton("Fechar", null)
                .create();
        entryDialog[0] = dialog;
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface dialogInterface) {
                Window window = ((AlertDialog) dialogInterface).getWindow();
                if (window != null) {
                    window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                }
            }
        });
        secure(dialog);
        dialog.show();
    }

    private void showEntryEditor(final VaultEntry entry) {
        if (!vaultReady()) {
            return;
        }
        final boolean creating = entry == null;
        final LinearLayout form = vertical(8);
        final EditText site = editField("Nome do site ou serviço", creating ? "" : entry.site, false);
        final AutoCompleteTextView username = usernameField(creating ? "" : entry.username);
        final EditText password = editField("Senha (opcional)", creating ? "" : entry.password, true);
        form.addView(site);
        form.addView(username, marginParams(0, 10, 0, 0));
        form.addView(password, marginParams(0, 10, 0, 0));
        TextView note = text("Os dados são gravados no armazenamento privado do app, criptografados com AES-GCM.\nNunca coloque uma senha real em uma exportação que você não reconheça.",
                12, Color.rgb(91, 105, 126));
        form.addView(note, marginParams(0, 14, 0, 0));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(creating ? "Nova senha" : "Editar senha")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Salvar", null)
                .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface dialogInterface) {
                final AlertDialog shown = (AlertDialog) dialogInterface;
                shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (!vaultReady()) {
                            shown.dismiss();
                            return;
                        }
                        String siteText = site.getText().toString().trim();
                        if (siteText.length() == 0) {
                            site.setError("Informe o site ou serviço");
                            site.requestFocus();
                            return;
                        }
                        try {
                            if (creating) {
                                store.add(new VaultEntry(siteText,
                                        username.getText().toString(), password.getText().toString()));
                            } else {
                                VaultEntry values = entry.copy();
                                values.site = siteText;
                                values.username = username.getText().toString();
                                values.password = password.getText().toString();
                                values.updatedAt = System.currentTimeMillis();
                                store.update(entry, values);
                            }
                            markDirty();
                            renderVault();
                            shown.dismiss();
                            Toast.makeText(MainActivity.this, "Registro salvo", Toast.LENGTH_SHORT).show();
                        } catch (Exception error) {
                            showError("Falha ao salvar o registro", error);
                        }
                    }
                });
                site.requestFocus();
                shown.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        secure(dialog);
        dialog.show();
    }

    private void confirmDelete(final VaultEntry entry, final AlertDialog entryDialog) {
        if (!vaultReady()) {
            return;
        }
        AlertDialog confirm = new AlertDialog.Builder(this)
                .setTitle("Apagar registro?")
                .setMessage("O acesso de “" + entry.site + "” será removido do cofre.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!vaultReady()) {
                            return;
                        }
                        try {
                            store.remove(entry);
                            markDirty();
                            renderVault();
                            if (entryDialog != null) {
                                entryDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "Registro apagado", Toast.LENGTH_SHORT).show();
                        } catch (Exception error) {
                            showError("Falha ao apagar o registro", error);
                        }
                    }
                }).create();
        secure(confirm);
        confirm.show();
    }

    // ---- Tarefas em segundo plano (PBKDF2, leitura e gravação de arquivos) ----

    private interface Work<T> {
        T run() throws Exception;
    }

    private interface Done<T> {
        void done(T result, Exception error);
    }

    private static final class TooLargeException extends Exception {
        TooLargeException() {
            super("Arquivo grande demais.");
        }
    }

    private static final class LoadedFile {
        final String text;
        final String format;

        LoadedFile(String text, String format) {
            this.text = text;
            this.format = format;
        }
    }

    private <T> void inBackground(final String progress, final Work<T> work, final Done<T> done) {
        final AlertDialog[] busy = new AlertDialog[1];
        if (progress != null) {
            LinearLayout body = horizontal(14);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(dp(20), dp(18), dp(20), dp(18));
            body.addView(new ProgressBar(this));
            body.addView(text(progress, 15, Color.rgb(18, 32, 54)), new LinearLayout.LayoutParams(-2, -2));
            AlertDialog dialog = new AlertDialog.Builder(this).setView(body).setCancelable(false).create();
            secure(dialog);
            dialog.show();
            busy[0] = dialog;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                T value = null;
                Exception failure = null;
                try {
                    value = work.run();
                } catch (Exception error) {
                    failure = error;
                } catch (OutOfMemoryError error) {
                    failure = new TooLargeException();
                }
                final T finalValue = value;
                final Exception finalFailure = failure;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (busy[0] != null && busy[0].isShowing()) {
                            busy[0].dismiss();
                        }
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        done.done(finalValue, finalFailure);
                    }
                });
            }
        });
    }

    private String friendlyMessage(Exception error) {
        if (error instanceof BackupCrypto.WrongPasswordException) {
            return "Senha incorreta ou arquivo alterado.";
        }
        if (error instanceof BackupCrypto.BackupFormatException || error instanceof JSONException) {
            return "Arquivo inválido ou incompleto.";
        }
        if (error instanceof TooLargeException) {
            return "Arquivo grande demais.";
        }
        if (error instanceof SecurityException) {
            return "Sem permissão para acessar o arquivo.";
        }
        if (error instanceof IOException) {
            return "Não foi possível ler ou gravar o arquivo.";
        }
        if (error instanceof GeneralSecurityException) {
            return "Não foi possível proteger ou abrir o backup.";
        }
        String message = error.getMessage();
        return message == null || message.length() == 0 ? "Tente novamente." : message;
    }

    private void lockedNotice() {
        Toast.makeText(this, "A bolsa foi bloqueada durante a operação. Desbloqueie e repita.",
                Toast.LENGTH_LONG).show();
    }

    private void markDirty() {
        prefs.edit().putBoolean("backupDirty", true).apply();
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(millis));
    }

    private String lastBackupLabel() {
        long last = prefs.getLong("lastBackupAt", 0);
        return last == 0 ? "Nenhum backup feito ainda."
                : "Último backup: " + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(new Date(last));
    }

    private void updateReminder() {
        if (reminderBand == null || store == null) {
            return;
        }
        long last = prefs.getLong("lastBackupAt", 0);
        boolean dirty = prefs.getBoolean("backupDirty", false);
        boolean show = vaultReady() && store.getEntries().size() > 0 && (last == 0 || dirty);
        if (!show) {
            reminderBand.setVisibility(View.GONE);
            return;
        }
        reminderText.setText(last == 0
                ? "Você ainda não tem backup protegido por senha. Sem ele, desinstalar o app ou trocar de aparelho apaga suas senhas."
                : "Há alterações desde o último backup (" + formatDate(last) + ").");
        reminderBand.setVisibility(View.VISIBLE);
    }

    private void renderEmptyState() {
        LinearLayout box = vertical(12);
        box.setPadding(dp(12), dp(36), dp(12), dp(36));
        TextView message = text("Sua bolsa está vazia.\nAdicione a primeira senha ou restaure um backup.",
                16, Color.rgb(220, 211, 242));
        message.setGravity(Gravity.CENTER);
        box.addView(message);
        Button add = button("Adicionar primeira senha", Color.rgb(111, 62, 186), Color.WHITE);
        add.setBackground(borderBackground(Color.rgb(111, 62, 186), Color.rgb(206, 158, 255), 12));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showEntryEditor(null);
            }
        });
        box.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        Button restore = button("Restaurar backup", Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        restore.setBackground(borderBackground(Color.rgb(49, 39, 83), Color.rgb(105, 82, 151), 12));
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startRestore();
            }
        });
        box.addView(restore, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        listContainer.addView(box, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, -2));
    }

    private void showBackupMenu() {
        if (!vaultReady()) {
            return;
        }
        LinearLayout body = vertical(10);
        body.addView(text(lastBackupLabel(), 14, Color.rgb(220, 211, 242)));
        final AlertDialog[] holder = new AlertDialog[1];
        Button exportItem = button("Exportar backup protegido por senha", Color.rgb(111, 62, 186), Color.WHITE);
        exportItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                holder[0].dismiss();
                showExportDialog();
            }
        });
        body.addView(exportItem, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        Button restoreItem = button("Restaurar backup", Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        restoreItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                holder[0].dismiss();
                startRestore();
            }
        });
        body.addView(restoreItem, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        Button csvItem = button("Importar lista CSV (Chrome, Google, Bitwarden…)",
                Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        csvItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                holder[0].dismiss();
                startCsvImport();
            }
        });
        body.addView(csvItem, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Backup")
                .setView(body)
                .setNegativeButton("Fechar", null)
                .create();
        holder[0] = dialog;
        secure(dialog);
        dialog.show();
    }

    private EditText passwordField(String hint) {
        return editField(hint, "", true);
    }

    private static char[] charsOf(EditText field) {
        int length = field.getText().length();
        char[] chars = new char[length];
        field.getText().getChars(0, length, chars, 0);
        return chars;
    }

    private static void setPasswordVisible(EditText field, boolean visible) {
        field.setInputType(visible
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSelection(field.getText().length());
    }

    private void showExportDialog() {
        if (!vaultReady()) {
            return;
        }
        LinearLayout form = vertical(8);
        final EditText password = passwordField("Senha do backup (mínimo 8 caracteres)");
        final EditText confirm = passwordField("Repita a senha");
        final TextView strength = text("Força: Muito fraca", 13, Color.rgb(220, 211, 242));
        password.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                strength.setText("Força: " + PasswordStrength.label(PasswordStrength.score(editable.toString())));
            }
        });
        CheckBox reveal = new CheckBox(this);
        reveal.setText("Mostrar senha");
        reveal.setTextColor(Color.rgb(220, 211, 242));
        reveal.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                setPasswordVisible(password, checked);
                setPasswordVisible(confirm, checked);
            }
        });
        TextView warning = text("Guarde essa senha: sem ela o backup não pode ser aberto, nem por você.",
                13, Color.rgb(255, 215, 112));
        form.addView(password);
        form.addView(confirm, marginParams(0, 10, 0, 0));
        form.addView(strength, marginParams(0, 6, 0, 0));
        form.addView(reveal);
        form.addView(warning, marginParams(0, 12, 0, 0));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Exportar backup")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Criar backup", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface shown) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (password.getText().length() < 8) {
                            password.setError("Use pelo menos 8 caracteres");
                            return;
                        }
                        if (!password.getText().toString().equals(confirm.getText().toString())) {
                            confirm.setError("As senhas não conferem");
                            return;
                        }
                        char[] chars = charsOf(password);
                        password.setText("");
                        confirm.setText("");
                        dialog.dismiss();
                        sealBackupWithPassword(chars);
                    }
                });
            }
        });
        secure(dialog);
        dialog.show();
    }

    private void sealBackupWithPassword(final char[] password) {
        final String plain;
        try {
            plain = store.buildBackupPlaintext();
        } catch (Exception error) {
            Arrays.fill(password, '\0');
            showError("Não foi possível preparar o backup", error);
            return;
        }
        inBackground("Protegendo o backup…", new Work<String>() {
            @Override
            public String run() throws Exception {
                try {
                    return VaultStore.sealBackup(plain, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        }, new Done<String>() {
            @Override
            public void done(String result, Exception error) {
                if (error != null) {
                    showError("Não foi possível proteger o backup", error);
                    return;
                }
                pendingExportText = result;
                startExportPicker();
            }
        });
    }

    private void startExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "bolsa-da-hermione-backup-"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".json");
        filePickerOpen = true;
        startActivityForResult(intent, REQUEST_BACKUP_EXPORT);
    }

    private OutputStream openForWrite(Uri uri) throws IOException {
        OutputStream output = null;
        try {
            output = getContentResolver().openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | FileNotFoundException error) {
            output = null;
        }
        if (output == null) {
            output = getContentResolver().openOutputStream(uri, "w");
        }
        if (output == null) {
            throw new IOException("Não foi possível abrir o destino");
        }
        return output;
    }

    /** 0 = conferido, 1 = não foi possível ler de volta, 2 = diferente do gravado. */
    private int verifyBackup(Uri uri, byte[] expected) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return 1;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return Arrays.equals(buffer.toByteArray(), expected) ? 0 : 2;
        } catch (IOException | RuntimeException error) {
            return 1;
        }
    }

    private void writeBackupFile(final Uri uri, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        inBackground("Gravando o backup…", new Work<Integer>() {
            @Override
            public Integer run() throws Exception {
                OutputStream output = openForWrite(uri);
                try {
                    output.write(bytes);
                    output.flush();
                } finally {
                    output.close();
                }
                return verifyBackup(uri, bytes);
            }
        }, new Done<Integer>() {
            @Override
            public void done(Integer result, Exception error) {
                if (error != null) {
                    showError("Não foi possível salvar o backup", error);
                    return;
                }
                if (result == 2) {
                    showMessage("Backup não confere", "O arquivo salvo não confere. Tente outro local.");
                    return;
                }
                prefs.edit()
                        .putLong("lastBackupAt", System.currentTimeMillis())
                        .putBoolean("backupDirty", false)
                        .apply();
                Toast.makeText(MainActivity.this, result == 0
                        ? "Backup salvo e conferido"
                        : "Backup salvo (não foi possível conferir a leitura)", Toast.LENGTH_LONG).show();
                updateReminder();
            }
        });
    }

    private String readText(Uri uri, int limit) throws Exception {
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("Não foi possível abrir o arquivo");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
                if (buffer.size() > limit) {
                    throw new TooLargeException();
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private void startRestore() {
        if (!vaultReady() && !recoveryMode) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerOpen = true;
        startActivityForResult(intent, REQUEST_RESTORE);
    }

    private void readAndDetect(final Uri uri) {
        inBackground("Lendo o arquivo…", new Work<LoadedFile>() {
            @Override
            public LoadedFile run() throws Exception {
                String text = readText(uri, MAX_BACKUP_BYTES);
                return new LoadedFile(text, VaultStore.detectFormat(text));
            }
        }, new Done<LoadedFile>() {
            @Override
            public void done(LoadedFile result, Exception error) {
                if (error != null) {
                    showError("Não foi possível ler o arquivo", error);
                    return;
                }
                handleBackupFile(result.text, result.format);
            }
        });
    }

    private void handleBackupFile(String text, String format) {
        if ("backup-v1".equals(format)) {
            askBackupPassword(text, null);
        } else if ("legacy".equals(format)) {
            openLegacyBackup(text);
        } else {
            showMessage("Arquivo não reconhecido", "Arquivo não reconhecido como backup da Bolsa da Hermione.");
        }
    }

    private void askBackupPassword(final String text, String problem) {
        LinearLayout form = vertical(8);
        if (problem != null) {
            form.addView(text(problem, 13, Color.rgb(255, 170, 170)));
        }
        final EditText password = passwordField("Senha do backup");
        form.addView(password, marginParams(0, 8, 0, 0));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Senha do backup")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Abrir", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface shown) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (password.getText().length() == 0) {
                            password.setError("Informe a senha");
                            return;
                        }
                        char[] chars = charsOf(password);
                        password.setText("");
                        dialog.dismiss();
                        openBackupWithPassword(text, chars);
                    }
                });
            }
        });
        secure(dialog);
        dialog.show();
    }

    private void openBackupWithPassword(final String text, final char[] password) {
        inBackground("Abrindo o backup…", new Work<VaultStore.ParsedBackup>() {
            @Override
            public VaultStore.ParsedBackup run() throws Exception {
                try {
                    return VaultStore.openBackup(text, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            }
        }, new Done<VaultStore.ParsedBackup>() {
            @Override
            public void done(VaultStore.ParsedBackup result, Exception error) {
                if (error instanceof BackupCrypto.WrongPasswordException) {
                    askBackupPassword(text, "Senha incorreta. Tente de novo.");
                    return;
                }
                if (error != null) {
                    showError("Não foi possível abrir o backup", error);
                    return;
                }
                offerRestore(result);
            }
        });
    }

    private void openLegacyBackup(final String text) {
        inBackground("Abrindo o backup…", new Work<VaultStore.ParsedBackup>() {
            @Override
            public VaultStore.ParsedBackup run() {
                return store.openLegacy(text);
            }
        }, new Done<VaultStore.ParsedBackup>() {
            @Override
            public void done(VaultStore.ParsedBackup result, Exception error) {
                if (error != null) {
                    showError("Backup da versão antiga", error);
                    return;
                }
                offerRestore(result);
            }
        });
    }

    private void offerRestore(final VaultStore.ParsedBackup backup) {
        String when = backup.exportedAt == 0 ? "de data desconhecida" : "de " + formatDate(backup.exportedAt);
        String message = "Backup " + when + " com " + backup.entries.size() + " registros.";
        if (recoveryMode) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Restaurar backup")
                    .setMessage(message)
                    .setPositiveButton("Restaurar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int which) {
                            restoreIntoRecovery(backup.entries);
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .create();
            secure(dialog);
            dialog.show();
            return;
        }
        if (!vaultReady()) {
            lockedNotice();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Restaurar backup")
                .setMessage(message)
                .setPositiveButton("Mesclar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        mergeBackup(backup.entries);
                    }
                })
                .setNeutralButton("Substituir tudo", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        confirmReplace(backup.entries);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .create();
        secure(dialog);
        dialog.show();
    }

    private void confirmReplace(final List<VaultEntry> entries) {
        if (!vaultReady()) {
            lockedNotice();
            return;
        }
        final int current = store.getEntries().size();
        if (current == 0) {
            replaceWith(entries);
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Substituir tudo?")
                .setMessage("Os " + current + " registros atuais serão trocados pelos do backup. "
                        + "Uma cópia fica guardada no aparelho.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Substituir", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        replaceWith(entries);
                    }
                })
                .create();
        secure(dialog);
        dialog.show();
    }

    private void mergeBackup(List<VaultEntry> entries) {
        if (!vaultReady()) {
            lockedNotice();
            return;
        }
        try {
            VaultStore.ImportResult result = store.merge(entries);
            markDirty();
            renderVault();
            Toast.makeText(this, result.added + " adicionados, " + result.updated + " atualizados, "
                    + result.skipped + " já existiam", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            showError("Falha ao restaurar o backup", error);
        }
    }

    private void replaceWith(List<VaultEntry> entries) {
        if (!vaultReady()) {
            lockedNotice();
            return;
        }
        try {
            store.replaceAll(entries);
            markDirty();
            renderVault();
            Toast.makeText(this, "Bolsa substituída: " + entries.size() + " registros.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            showError("Falha ao restaurar o backup", error);
        }
    }

    private void restoreIntoRecovery(List<VaultEntry> entries) {
        try {
            store.startFresh();
            store.replaceAll(entries);
            recoveryMode = false;
            markDirty();
            finishUnlock();
        } catch (Exception error) {
            showError("Falha ao restaurar o backup", error);
        }
    }

    private void confirmFreshStart() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Começar bolsa nova?")
                .setMessage("O arquivo ilegível fica guardado à parte, não é apagado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Começar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        try {
                            store.startFresh();
                            recoveryMode = false;
                            finishUnlock();
                        } catch (Exception error) {
                            showError("Não foi possível começar uma bolsa nova", error);
                        }
                    }
                })
                .create();
        secure(dialog);
        dialog.show();
    }

    private void startCsvImport() {
        if (!vaultReady()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/*", "text/csv", "text/comma-separated-values", "application/csv"});
        filePickerOpen = true;
        startActivityForResult(intent, REQUEST_CSV);
    }

    private void readCsv(final Uri uri) {
        inBackground("Lendo a lista…", new Work<List<CsvImport.Row>>() {
            @Override
            public List<CsvImport.Row> run() throws Exception {
                return CsvImport.parse(readText(uri, MAX_CSV_BYTES));
            }
        }, new Done<List<CsvImport.Row>>() {
            @Override
            public void done(final List<CsvImport.Row> rows, Exception error) {
                if (error != null) {
                    showError("Não foi possível importar a lista", error);
                    return;
                }
                if (rows.isEmpty()) {
                    showMessage("Lista vazia", "Nenhum registro encontrado no arquivo.");
                    return;
                }
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Importar lista CSV")
                        .setMessage(rows.size() + " registros encontrados. Os repetidos serão ignorados.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Importar", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                importRows(rows);
                            }
                        })
                        .create();
                secure(dialog);
                dialog.show();
            }
        });
    }

    private void importRows(List<CsvImport.Row> rows) {
        if (!vaultReady()) {
            lockedNotice();
            return;
        }
        ArrayList<VaultEntry> entries = new ArrayList<>();
        for (CsvImport.Row row : rows) {
            VaultEntry entry = new VaultEntry(row.site, row.username, row.password);
            entry.notes = row.notes;
            entries.add(entry);
        }
        try {
            VaultStore.ImportResult result = store.merge(entries);
            markDirty();
            renderVault();
            showMessage("Lista importada", result.added + " adicionados, " + result.skipped
                    + " já existiam. Apague o arquivo CSV: ele guarda senhas sem criptografia.");
        } catch (Exception error) {
            showError("Falha ao importar a lista", error);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREDENTIAL) {
            authInProgress = false;
            if (resultCode == RESULT_OK) {
                unlockAndLoad();
            } else {
                autoPromptAllowed = false;
                showLocked("Bolsa bloqueada", "Toque em Desbloquear para tentar de novo.", false);
            }
            return;
        }
        if (requestCode == REQUEST_BACKUP_EXPORT) {
            filePickerOpen = false;
            String text = pendingExportText;
            pendingExportText = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && text != null) {
                writeBackupFile(data.getData(), text);
            }
            return;
        }
        if (requestCode == REQUEST_RESTORE) {
            filePickerOpen = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                readAndDetect(data.getData());
            }
            return;
        }
        if (requestCode == REQUEST_CSV) {
            filePickerOpen = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                readCsv(data.getData());
            }
        }
    }

    private LinearLayout.LayoutParams marginedFullWidth(int top, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.setMargins(0, dp(top), 0, 0);
        return params;
    }

    private void copyFromEntry(String label, VaultEntry entry, String value) {
        if (!vaultReady()) {
            return;
        }
        copyToClipboard(label, value);
        try {
            store.recordAccess(entry);
        } catch (Exception error) {
            showError("Não foi possível registrar o acesso", error);
        }
    }

    private void copyToClipboard(String label, String value) {
        if (value == null || value.length() == 0) {
            Toast.makeText(this, "Esse campo está vazio", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, value);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        clipboardText = value;
        handler.removeCallbacks(clearClipRunnable);
        handler.postDelayed(clearClipRunnable, CLIPBOARD_CLEAR_MS);
        Toast.makeText(this, "Senha".equals(label)
                ? "Senha copiada. Será apagada da área de transferência em 30 s."
                : label + " copiado. A área de transferência será limpa em 30 s.", Toast.LENGTH_SHORT).show();
    }

    /**
     * Em segundo plano o Android 10+ não deixa ler a área de transferência, então quando
     * não dá para conferir a limpeza é feita mesmo assim. Se o conteúdo atual for outro
     * texto (o usuário copiou algo depois), ele é mantido.
     */
    private void clearClipboardNow() {
        if (clipboardText == null) {
            return;
        }
        String copied = clipboardText;
        clipboardText = null;
        handler.removeCallbacks(clearClipRunnable);
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData current = null;
            try {
                current = clipboard.getPrimaryClip();
            } catch (RuntimeException error) {
                current = null;
            }
            if (current == null || current.getItemCount() == 0) {
                clipboard.clearPrimaryClip();
                return;
            }
            CharSequence text = current.getItemAt(0).coerceToText(this);
            if (text != null && copied.contentEquals(text)) {
                clipboard.clearPrimaryClip();
            }
        } catch (RuntimeException error) {
            // Sem como limpar agora; o sistema controla a área de transferência.
        }
    }

    private void showError(String title, Exception error) {
        showMessage(title, friendlyMessage(error));
    }

    private EditText editField(String hint, String value, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(borderBackground(Color.WHITE, Color.rgb(210, 220, 234), 12));
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        return field;
    }

    private String dateLabel(VaultEntry entry) {
        boolean modified = entry.updatedAt > entry.createdAt;
        String prefix = modified ? "Modificada em " : "Criada em ";
        return prefix + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(new Date(entry.updatedAt));
    }

    private AutoCompleteTextView usernameField(String value) {
        final AutoCompleteTextView field = new AutoCompleteTextView(this);
        field.setHint("Usuário (opcional)");
        field.setText(value);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setThreshold(0);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(borderBackground(Color.WHITE, Color.rgb(210, 220, 234), 12));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, usernamesInVault());
        field.setAdapter(adapter);
        field.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus && adapter.getCount() > 0) {
                    field.post(new Runnable() {
                        @Override
                        public void run() {
                            field.showDropDown();
                        }
                    });
                }
            }
        });
        field.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (adapter.getCount() > 0) {
                    field.showDropDown();
                }
            }
        });
        return field;
    }

    private ArrayList<String> usernamesInVault() {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (VaultEntry entry : store.getEntries()) {
            String username = entry.username == null ? "" : entry.username.trim();
            if (username.length() == 0) {
                continue;
            }
            String normalized = username.toLowerCase(Locale.ROOT);
            if (!unique.containsKey(normalized)) {
                unique.put(normalized, username);
            }
        }
        ArrayList<String> usernames = new ArrayList<>(unique.values());
        Collections.sort(usernames, String.CASE_INSENSITIVE_ORDER);
        return usernames;
    }

    private static String mask(String value) {
        return value == null || value.length() == 0 ? "Não informada" : "••••••••";
    }

    private LinearLayout horizontal(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        if (spacing > 0) {
            layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            layout.setDividerDrawable(new SpaceDrawable(dp(spacing), 1));
        }
        return layout;
    }

    private LinearLayout vertical(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (spacing > 0) {
            layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            layout.setDividerDrawable(new SpaceDrawable(1, dp(spacing)));
        }
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(borderBackground(backgroundColor, backgroundColor, 12));
        return button;
    }

    private GradientDrawable magicBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(16, 11, 43), Color.rgb(40, 24, 78), Color.rgb(13, 43, 72)});
        background.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return background;
    }

    private GradientDrawable borderBackground(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != fill) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class SpaceDrawable extends android.graphics.drawable.ColorDrawable {
        private final int width;
        private final int height;

        SpaceDrawable(int width, int height) {
            super(Color.TRANSPARENT);
            this.width = width;
            this.height = height;
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }
    }

}
