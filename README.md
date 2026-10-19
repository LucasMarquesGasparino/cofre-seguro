# Bolsa da Hermione (Cofre Seguro)

Gerenciador local de senhas para Android, sem rede e sem dependências externas.
Versão 1.1.0 (versionCode 2). Pacote `com.cofreseguro`.

## Recursos

- Cadastro, edição e exclusão de site, usuário, senha e notas.
- Favoritos, busca (site, usuário, senha e notas) e ordenação (alfabética, mais
  acessadas, senhas repetidas, mais recentes). Favoritos aparecem primeiro.
- Alertas de senha repetida e senha fraca, com contagem no topo da lista.
- Gerador de senhas sem caracteres ambíguos (8 a 40, com maiúsculas, números e símbolos).
- Indicador de força da senha (estimativa simples, 0 a 4).
- Cópia de usuário e senha; a área de transferência é marcada como sensível e
  limpa após 30 segundos, mesmo com o app em segundo plano.
- Bloqueio por biometria ou PIN/padrão do aparelho, ao sair do app (tempo escolhido
  em Ajustes), ao desligar a tela e após 5 minutos sem uso. FLAG_SECURE em todas as telas e diálogos.
- Backup protegido por senha, restauração (mesclar ou substituir tudo) e importação
  de listas CSV (Chrome/Google, Bitwarden, Firefox e planilhas genéricas).

## Armazenamento

- Bolsa local em `files/vault.enc.json`, cifrada com AES-256-GCM. A chave fica no
  Android Keystore, não exportável. Cópia anterior em `vault.enc.json.bak`.
- Arquivo ilegível: o app tenta a cópia anterior; se não houver, oferece tentar de
  novo, restaurar um backup ou começar uma bolsa nova (o arquivo ilegível é guardado, não apagado).

## Backup

O backup (`bolsa-hermione-backup-v1`) é um JSON com a chave derivada da senha que
você escolhe: PBKDF2-HMAC-SHA256 (600 000 iterações, sal de 16 bytes) e AES-256-GCM
(IV de 12 bytes, tag de 128 bits, metadados autenticados). Sem a senha, o backup
não pode ser aberto, nem pelo app. Ele serve para trocar de aparelho ou reinstalar.

**Atenção:** desinstalar o app apaga a bolsa local. Um backup protegido por senha
é o único jeito de recuperar as senhas depois disso. Backups do formato antigo
(versão 1.0) só abrem na mesma instalação, pois dependem da chave do Keystore.

A lista CSV de importação não é criptografada: apague o arquivo depois de importar.

## Compilar e testar

```sh
cd ~/projects/cofre-seguro
sh build.sh
```

`build.sh` roda os testes da lógica pura (`tests/run.sh`, JVM, sem Android) antes de
empacotar. Assina com `~/.android-keys/cofre-seguro.keystore` (criado se não existir)
e copia o APK para `/storage/emulated/0/Documents/APK/Bolsa-da-Hermione.apk`.

## Estrutura

```
src/com/cofreseguro/
├── MainActivity.java   # UI, biometria, bloqueio, backup e restauração
├── VaultStore.java     # bolsa: leitura, escrita atômica, mutações, backup
├── VaultEntry.java     # registro
├── CryptoStore.java    # chave do Keystore e formato local
├── BackupCrypto.java   # backup protegido por senha (pura)
├── PasswordGenerator.java / PasswordStrength.java / CsvImport.java  (puras)
tests/                  # PureLogicTest + run.sh
res/                    # ícone, estilos, regras de extração de dados
tools/                  # ajuste de metadados para o D8
```
