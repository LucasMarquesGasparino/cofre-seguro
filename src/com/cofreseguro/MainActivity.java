package com.cofreseguro;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.hardware.biometrics.BiometricPrompt;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
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
    private static final int REQUEST_EXPORT = 4102;
    private static final int REQUEST_IMPORT = 4103;
    private static final int SORT_ALPHABETICAL = 0;
    private static final int SORT_ACCESS = 1;
    private static final int SORT_DUPLICATES = 2;
    private static final int SORT_UPDATED = 3;

    private final Handler handler = new Handler();
    private VaultStore store;
    private KeyguardManager keyguardManager;

    private FrameLayout root;
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
    private int sortMode = SORT_ALPHABETICAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManagerLayoutParams.FLAG_SECURE,
                WindowManagerLayoutParams.FLAG_SECURE);
        keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        store = new VaultStore(this);
        buildUi();
        uiReady = true;
        showLocked("Bolsa bloqueada", "Desbloqueie com a biometria ou com o bloqueio de tela do aparelho.", false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                requestUnlock();
            }
        }, 300);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uiReady && !unlocked && !authInProgress) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestUnlock();
                }
            }, 250);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (unlocked && !filePickerOpen) {
            lockAndHide();
        }
    }

    @Override
    protected void onDestroy() {
        if (store != null) {
            store.clearMemory();
        }
        super.onDestroy();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackground(magicBackground());

        ScrollView scrollView = new ScrollView(this);
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
        Button exportButton = button("Exportar", Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        exportButton.setBackground(borderBackground(Color.rgb(49, 39, 83), Color.rgb(105, 82, 151), 12));
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportVault();
            }
        });
        actionRow.addView(exportButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button importButton = button("Importar", Color.rgb(49, 39, 83), Color.rgb(232, 222, 255));
        importButton.setBackground(borderBackground(Color.rgb(49, 39, 83), Color.rgb(105, 82, 151), 12));
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                importVault();
            }
        });
        actionRow.addView(importButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(actionRow, marginParams(0, 22, 0, 0));

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
        lockedOverlay.setBackgroundColor(Color.TRANSPARENT);
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
                BiometricPrompt.Builder builder = new BiometricPrompt.Builder(this)
                        .setTitle("Desbloquear Bolsa da Hermione")
                        .setSubtitle("Confirme sua identidade para ver as credenciais")
                        .setDescription("A chave do cofre permanece protegida pelo Android");
                if (Build.VERSION.SDK_INT >= 29) {
                    builder.setDeviceCredentialAllowed(true);
                } else {
                    builder.setNegativeButton("Usar PIN ou padrão", executor,
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    launchDeviceCredential();
                                }
                            });
                }
                BiometricPrompt prompt = builder.build();
                prompt.authenticate(null, executor, new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        unlockAndLoad();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        authInProgress = false;
                        showLocked("Bolsa bloqueada", "Toque no botão para tentar novamente.", false);
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                        Toast.makeText(MainActivity.this, "Biometria não reconhecida", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (RuntimeException error) {
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
        startActivityForResult(intent, REQUEST_CREDENTIAL);
    }

    private void unlockAndLoad() {
        authInProgress = false;
        try {
            store.load();
            unlocked = true;
            lockedOverlay.setVisibility(View.GONE);
            sortSpinner.setEnabled(true);
            renderVault();
        } catch (Exception error) {
            unlocked = false;
            showLocked("Não foi possível abrir o cofre",
                    "O arquivo pode estar corrompido ou a chave do Android foi redefinida.", false);
            showError("Falha ao abrir o cofre", error);
        }
    }

    private void lockAndHide() {
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
        lockedOverlay.setVisibility(View.VISIBLE);
    }

    private void renderVault() {
        if (!unlocked) {
            return;
        }
        listContainer.removeAllViews();
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
            String emptyMessage = query.length() == 0
                    ? "Sua bolsa está vazia.\nAdicione o primeiro acesso acima."
                    : "Nenhum registro combina com essa busca.";
            TextView empty = text(emptyMessage, 16, Color.rgb(220, 211, 242));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(44), dp(12), dp(44));
            listContainer.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, -2));
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
        entry.accessCount++;
        try {
            store.save();
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
                copyToClipboard("Usuário", entry.username);
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
                copyToClipboard("Senha", entry.password);
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
        dialog.show();
    }

    private void showEntryEditor(final VaultEntry entry) {
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
                        String siteText = site.getText().toString().trim();
                        if (siteText.length() == 0) {
                            site.setError("Informe o site ou serviço");
                            site.requestFocus();
                            return;
                        }
                        if (creating) {
                            store.getEntries().add(new VaultEntry(siteText,
                                    username.getText().toString(), password.getText().toString()));
                        } else {
                            entry.site = siteText;
                            entry.username = username.getText().toString();
                            entry.password = password.getText().toString();
                            entry.updatedAt = System.currentTimeMillis();
                        }
                        try {
                            store.save();
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
        dialog.show();
    }

    private void confirmDelete(final VaultEntry entry, final AlertDialog entryDialog) {
        new AlertDialog.Builder(this)
                .setTitle("Apagar registro?")
                .setMessage("O acesso de “" + entry.site + "” será removido do cofre.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        store.getEntries().remove(entry);
                        try {
                            store.save();
                            renderVault();
                            if (entryDialog != null) {
                                entryDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "Registro apagado", Toast.LENGTH_SHORT).show();
                        } catch (Exception error) {
                            showError("Falha ao apagar o registro", error);
                        }
                    }
                }).show();
    }

    private void exportVault() {
        try {
            store.save();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "cofre-seguro-backup.enc.json");
            filePickerOpen = true;
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception error) {
            showError("Falha ao preparar a exportação", error);
        }
    }

    private void importVault() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerOpen = true;
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREDENTIAL) {
            authInProgress = false;
            if (resultCode == RESULT_OK) {
                unlockAndLoad();
            } else {
                showLocked("Bolsa bloqueada", "Toque no botão para tentar novamente.", false);
            }
            return;
        }
        if (requestCode == REQUEST_EXPORT || requestCode == REQUEST_IMPORT) {
            filePickerOpen = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    if (requestCode == REQUEST_EXPORT) {
                        store.exportTo(getContentResolver(), data.getData());
                        Toast.makeText(this, "Backup criptografado exportado", Toast.LENGTH_LONG).show();
                    } else {
                        store.importFrom(getContentResolver(), data.getData());
                        Toast.makeText(this, "Backup criptografado importado", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception error) {
                    showError("Falha no backup criptografado", error);
                }
            }
            lockAndHide();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestUnlock();
                }
            }, 300);
        }
    }

    private void copyToClipboard(String label, String value) {
        if (value == null || value.length() == 0) {
            Toast.makeText(this, "Esse campo está vazio", Toast.LENGTH_SHORT).show();
            return;
        }
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        final String copied = value;
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, "Copiado; o conteúdo será limpo em 30 segundos", Toast.LENGTH_SHORT).show();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null
                        && clipboard.getPrimaryClip().getItemCount() > 0
                        && copied.equals(clipboard.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this).toString())) {
                    clipboard.clearPrimaryClip();
                }
            }
        }, 30_000);
    }

    private void showError(String title, Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.length() == 0) {
            detail = "Verifique o armazenamento e tente novamente.";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setPositiveButton("OK", null)
                .show();
    }

    private EditText editField(String hint, String value, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(borderBackground(Color.WHITE, Color.rgb(210, 220, 234), 12));
        if (password) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT);
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
        field.setInputType(InputType.TYPE_CLASS_TEXT);

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

    private static final class WindowManagerLayoutParams {
        static final int FLAG_SECURE = 8192;
    }
}
