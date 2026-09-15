# Bolsa da Hermione (Cofre Seguro)

Gerenciador local de credenciais para Android, sem rede e sem dependências
externas. Tema noturno mágico, detalhes dourados e fundo em degradê leve.

- Pacote: `com.cofreseguro` (nome exibido: **Bolsa da Hermione**)
- Arquivo local **criptografado com AES-GCM** e chave não exportável no
  Android Keystore (`CryptoStore`/`VaultStore`).

## Recursos

- Cadastro, edição e exclusão de site, usuário e senha.
- Sugestões com autocomplete para usuários já usados.
- Busca por site, usuário ou senha (ativa com +6 registros).
- Data de criação/modificação e ordenação por mais recentes; modos
  alfabética, mais acessadas e senhas iguais.
- Olho no topo alterna visibilidade; o bloqueio sempre restaura o oculto.
- Cópia separada de usuário e senha, com limpeza da área de transferência
  após 30 segundos.
- Biometria quando disponível; PIN/padrão/senha do bloqueio como alternativa.
- Bloqueio ao sair do app + bloqueio de capturas de tela.
- Exportação/importação de backup sem expor registros em texto puro.

## Limitação do backup

O backup exportado conserva os registros, mas **não** a chave do Keystore:
restaurar em outro aparelho exige reimportação assistida (ver telas de
Exportar/Importar).

## Compilar

```sh
cd ~/projects/cofre-seguro
bash build.sh
```

APKs `Bolsa-da-Hermione.apk` / `Cofre-Seguro.apk` em `build/`.

## Estrutura

```
cofre-seguro/
├── src/com/cofreseguro/
│   ├── MainActivity.java  # UI + biometria + bloqueio
│   ├── VaultEntry.java / VaultStore.java / CryptoStore.java
└── res/ / tools/
```
