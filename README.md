# Bolsa da Hermione

Gerenciador local de credenciais para Android, sem rede e sem dependências externas.

## Recursos

- Cadastro, edição e exclusão de site, usuário e senha.
- Sugestões com autocomplete para usuários já usados, mantendo a possibilidade de digitar um novo.
- Busca por site, usuário ou senha, ativada automaticamente quando há mais de 6 registros.
- Data de criação/modificação em cada registro e ordenação por registros mais recentes.
- Interface com tema noturno mágico, detalhes dourados e fundo em degradê leve.
- Lista por ordem alfabética, quantidade de acessos ou grupos de senhas iguais.
- Botão de olho no topo para alternar a visibilidade das senhas na lista; o bloqueio sempre restaura o modo oculto.
- Cópia separada de usuário e senha, com limpeza automática da área de transferência após 30 segundos.
- Biometria quando disponível e PIN, padrão ou senha do bloqueio do Android como alternativa.
- Bloqueio ao deixar o aplicativo e bloqueio de capturas de tela.
- Arquivo local criptografado com AES-GCM e chave não exportável no Android Keystore.
- Exportação e importação de backup sem expor os registros em texto puro.

## Limitação do backup

O backup exportado contém o cofre criptografado, mas não contém a chave. A chave fica no Android Keystore da instalação/aparelho. Portanto, o backup é uma cópia de segurança para a mesma instalação; desinstalar o aplicativo ou redefinir o Keystore pode tornar o arquivo irrecuperável.

## Gerar o APK

```sh
sh build.sh
```

O APK assinado localmente será gerado em `build/Bolsa-da-Hermione.apk` e copiado para `internal/documents/Bolsa-da-Hermione.apk`.

O app usa uma identidade visível e uma entrada normal no launcher. Não há mecanismo oculto, nome disfarçado ou acionamento secreto.
