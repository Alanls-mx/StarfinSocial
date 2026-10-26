# StarfinSocial — Fase 4.1

Plugin independente de Party para rede BungeeCord com servidores Spigot 1.8.8. Compila com Java 8. Não altera o StarfinBedWars nem o matchmaking do StarfinMinigames.

## Módulos

| Módulo | Responsabilidade |
| --- | --- |
| `starfinsocial-api` | Contratos assíncronos, snapshots imutáveis e eventos sem dependências de Bukkit/Bungee. |
| `starfinsocial-common` | Regras, migração MySQL e operações transacionais. |
| `starfinsocial-bungee` | Comandos, sessões da rede, alterações de Party, chat e entrega de notificações. |
| `starfinsocial-spigot` | Serviço `PartyDirectory` somente leitura para plugins Bukkit. |

O MySQL é a autoridade compartilhada. Cada alteração relevante da Party bloqueia sua linha (`SELECT ... FOR UPDATE`) e confirma membro, liderança, convite e notificação na mesma transação. `ss_members.player_uuid` impede filiação simultânea a duas Parties. O proxy usa UUIDs obtidos de `ProxiedPlayer`, nunca nomes como chave persistente. Nomes informados em comandos são resolvidos no banco e recusados se forem ambíguos.

As instâncias de BungeeCord gravam presença com um ID novo para cada sessão e atualizam essa presença a cada dois segundos. Desconexões marcam a sessão como encerrada; troca de servidor apenas muda `server_name`. Um proxy que cai deixa a presença expirar. Notificações são gravadas na tabela `ss_notifications`, reclamadas por sessão com prazo e marcadas como entregues antes de serem enviadas ao cliente. Assim não dependem de mensagens Spigot que exigem jogador conectado. O chat mantém a ordem dos IDs de notificação por destinatário; uma queda entre marcar entregue e enviar pode perder a mensagem. Esta é a escolha para evitar duplicatas após uma falha. Não há entrega garantida exatamente uma vez sob falha de processo/rede.

## Instalação

1. Execute `mvn -B clean verify` na raiz do projeto.
2. Copie `starfinsocial-bungee/target/StarfinSocial-Bungee.jar` para `plugins/` de **cada** proxy.
3. Copie `starfinsocial-spigot/target/StarfinSocial-Spigot.jar` para `plugins/` dos servidores Spigot que precisam da API local de leitura. O módulo Spigot é opcional para o funcionamento dos comandos no proxy.
4. Edite `plugins/StarfinSocial/config.yml` em cada instância com o mesmo banco MySQL, usuário e senha. Use um `proxy-id` distinto em cada proxy. Credenciais não estão no código.
5. Reinicie proxy e Spigot. O proxy cria apenas as tabelas `ss_` na migração v1. O Spigot não executa migrações nem altera Parties.

O proxy e cada servidor Spigot têm configurações próprias. `mysql.ssl-mode` admite `DISABLED`, `PREFERRED`, `REQUIRED`, `VERIFY_CA` ou `VERIFY_IDENTITY`. Para um ambiente externo, configure TLS conforme o banco. A JAR contém o driver MySQL e Hikari isolados por relocation; não requer StarfinCore no proxy nem no Spigot.

Arquivos de tradução: `messages_pt_BR.yml` (padrão) e `messages_en_US.yml`. Configure `locale` no proxy. Chaves ausentes usam o texto em português. Placeholders principais: `{player}`, `{leader}`, `{party}`, `{members}`, `{max_members}`, `{server}`, `{privacy}`, `{message}`, `{setting}`. Mensagens aceitam `&` para cores; texto do chat enviado por jogadores tem códigos de cor e caracteres de controle removidos.

## Comandos

| Comando | Ação |
| --- | --- |
| `/party` ou `/party help` | Ajuda. |
| `/party create` | Cria Party com o limite permitido. |
| `/party invite <jogador>` | Convida jogador conectado na rede. Líder ou oficial. |
| `/party accept <jogador>` / `/party deny <jogador>` | Aceita ou recusa convite daquele remetente. |
| `/party join <líder>` | Entra em Party `OPEN`, se houver vaga. |
| `/party leave` | Sai; líder com membros deve transferir a liderança antes. |
| `/party kick <jogador>` | Líder remove membro. |
| `/party promote <jogador>` | Líder promove membro a oficial. |
| `/party transfer <jogador>` | Líder transfere a liderança. |
| `/party disband` | Líder encerra a Party. |
| `/party list` / `/party info` | Mostra membros ou resumo. |
| `/party chat` | Liga/desliga modo de chat da Party. |
| `/party chat <mensagem>` / `/pc <mensagem>` | Envia mensagem à Party. |
| `/party settings` | Mostra privacidade, chat e limite. |
| `/party settings privacy open\|invite_only\|closed` | Líder altera privacidade. |
| `/party settings chat on\|off` | Líder habilita/desabilita chat. |
| `/party settings size <n>` | Líder define limite dentro do permitido. |

O alias `/p` equivale a `/party`. `/p` e `/pc` só são registrados se não ocupados por outro plugin; o comando principal também verifica conflito. O convite fornece botões clicáveis Aceitar e Recusar no cliente 1.8.8.

## Permissões e limites

Nós: `starfinsocial.party.use`, `.create`, `.invite`, `.kick`, `.promote`, `.transfer`, `.disband`, `.chat`, `.settings` e `.admin`. O arquivo do proxy contém `party.permissions.default-actions`; por padrão essas ações normais são liberadas a jogadores. Remova uma ação dessa lista para exigir o respectivo nó do BungeeCord. `.admin` libera os comandos, mas **não** ignora validações de liderança e filiação no banco. Não existe comando de alteração forçada de Party nesta fase.

Permissões `starfinsocial.party.size.4`, `.8`, `.16`, `.32` ampliam o limite. Regra: maior entre o limite padrão e os tiers concedidos, limitado por `absolute-max-members`. Com padrão de 16, `.4` e `.8` não reduzem o limite; `.32` amplia para 32 se o teto absoluto permitir. O líder pode baixar o limite de uma Party até o número atual de membros.

Convites expiram após 60 segundos por padrão. O mesmo remetente tem intervalo padrão de 3 segundos entre convites; mensagens de chat têm intervalo de 1 segundo. A privacidade `CLOSED` bloqueia novos convites e cancela os pendentes, `INVITE_ONLY` exige convite, e `OPEN` também permite `/party join`. Convite e entrada conferem novamente autoridade do remetente, vaga, estado e filiação no instante da transação.

O líder que se desconecta mantém sua posição por 120 segundos por padrão. Se voltar dentro do prazo, continua líder. Após o prazo, a Party passa para um oficial conectado; se não houver, para o membro conectado mais antigo. Se ninguém estiver elegível, a Party expira e libera os membros. A eleição é serializada pelo bloqueio da linha da Party. A varredura acontece aproximadamente a cada dez segundos.

## Tabelas MySQL v1

| Tabela | Função |
| --- | --- |
| `ss_schema_migrations` | Versões aplicadas. |
| `ss_parties` | Estado, líder, limite, privacidade, chat, revisão, prazo de ausência. |
| `ss_members` | Membros e cargo; chave primária no UUID. |
| `ss_invites` | Convites e seus cinco estados; índice único em convite pendente por Party/alvo. |
| `ss_presence` | Nome recente, proxy, sessão, servidor e heartbeat. |
| `ss_cooldowns` | Limites de frequência. |
| `ss_notifications` | Caixa de saída compartilhada para chat, convites e mudanças. |

Não há tabelas de amizades ou clãs nesta fase. As migrações são idempotentes; a versão 1 cria apenas as tabelas acima. Uma evolução futura deve adicionar uma versão nova e preservar os dados existentes.

## API pública

No BungeeCord, obtenha o plugin `StarfinSocial` e use `((StarfinSocialBungee) plugin).partyService()`. O contrato `PartyService` retorna `CompletableFuture` para criação, convite, aceite, recusa, entrada, saída, liderança, administração, configurações, chat e `planTransfer`. `planTransfer` é apenas uma verificação de capacidade com a revisão observada; não reserva vagas nem desloca jogadores. Integração completa com reservas e matchmaking pertence à Fase 4.4.

No Spigot, consulte `Bukkit.getServicesManager().getRegistration(PartyDirectory.class)`. `PartyDirectory` retorna `CompletableFuture<PartySnapshot>` para leituras de membros, cargo, servidor e estado. Nunca bloqueie a thread principal esperando o resultado nem tente modificar a coleção imutável do snapshot. A API Spigot não oferece mutações; o proxy decide o estado da rede.

`PartyService.subscribe` registra `PartyEventListener`. Eventos `CREATE`, `DISBAND`, `INVITE`, `JOIN`, `LEAVE`, `KICK`, `PROMOTE`, `LEADER_CHANGE`, `SETTINGS_CHANGE` e `CHAT` são publicados **após commit** para operações originadas naquele proxy. Para sincronização distribuída, consulte o banco por API; não use eventos locais como fonte de verdade em múltiplos proxies. Eventos canceláveis antes do commit não foram oferecidos porque veto assíncrono em várias instâncias não preservaria a transação.

As mutações da API verificam a sessão autenticada, a ação permitida e o limite de membros da mesma forma que os comandos. Plugins consumidores são compilados contra `starfinsocial-api` com escopo `provided`; não empacote outra cópia das classes da API. No Spigot, declare `softdepend: [StarfinSocial]` se for usar o serviço opcional.

## Testes

`mvn -B clean verify` compila ambos os plugins. Para executar a suíte MySQL de integração em um banco descartável, defina `SOCIAL_TEST_JDBC`, `SOCIAL_TEST_USER` e `SOCIAL_TEST_PASSWORD`; a suíte limpa as tabelas `ss_` desse banco em cada caso. **Nunca aponte a suíte para o banco da rede.** Sem essas variáveis, os casos MySQL são ignorados. Exemplo de URL local: `jdbc:mysql://127.0.0.1:3308/starfinsocial_test?sslMode=DISABLED&allowPublicKeyRetrieval=true`.

Testes manuais recomendados: autenticar dois clientes em servidores diferentes, criar/aceitar Party por comando e por botão clicável, trocar `/pc`, alternar chat, testar limites e cooldown, transferir liderança, reiniciar o proxy e verificar persistência, desconectar/reconectar o líder antes e depois do prazo, e simular MySQL indisponível. Verifique alias `/p` e `/pc` quando outros plugins estiverem instalados.

Nesta entrega foram executados testes automatizados contra MySQL 8 descartável e uma rodada em servidor real com dois clientes Minecraft 1.8.8: convite, aceite, `/pc` entre `auth` e `survival`, lista e transferência de liderança. Um cliente sem registro tentou `/party info` e recebeu bloqueio de autenticação; após login, o comando respondeu normalmente. O teste dos botões clicáveis como ação do cliente, o fluxo de múltiplos proxies reais e a falha de processo no instante de entrega da caixa de saída continuam manuais.

## Limites desta fase

- A autenticação de contas offline continua sob responsabilidade do StarfinLogin. Quando ele está instalado, o StarfinSocial consulta sua sessão atual e só aceita comandos, mutações de API e entrega de notificações no estado `AUTHENTICATED`. Esta ponte usa reflexão porque o StarfinLogin ainda não expõe um contrato público de autenticação. Se a estrutura interna mudar, o StarfinSocial bloqueia essas operações até a integração ser atualizada. Sem StarfinLogin, a rede deve fornecer UUIDs autenticados no próprio proxy ou em outro mecanismo de confiança.
- `PartyTransferPlan` faz apenas preflight. Não reserva vaga, não envia a Party para arena e não altera BedWars.
- Presença é renovada por heartbeat, então detectar uma queda abrupta pode levar até `presence-timeout-seconds` mais o intervalo da varredura.
- A caixa de saída prioriza não duplicar notificações; uma queda exatamente após marcar uma mensagem como entregue pode perdê-la. Um transporte/ack de cliente pode ser acrescentado depois.
- Uma Party de líder único é encerrada quando ele usa `/party leave`. Uma Party com outros membros exige transferir liderança ou usar `/party disband`.

Amizades, clãs, preferências sociais por jogador e matchmaking em grupo ficam para os próximos prompts.
