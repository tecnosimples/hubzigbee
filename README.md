# HubZigbee — Hubitat (TecnoSimples)

Gateway Zigbee satélite para Hubitat: os aparelhos pareados no HubZigbee chegam
ao hub como devices nativos (interruptor, teclas, contato, energia).
**Produto licenciado da TecnoSimples Tecnologia LTDA.** Requer o hardware HubZigbee.

## Instalação (via Hubitat Package Manager)

1. No HPM, escolha **Install** → **From a URL**.
2. Cole a URL do **packageManifest.json** (atenção: é o manifesto, NÃO o repository.json):
   `https://raw.githubusercontent.com/tecnosimples/hubzigbee/main/packageManifest.json`
3. Avance — deve aparecer **"You are about to install HubZigbee"**. Conclua (o HPM baixa os 2 drivers).

> Se aparecer **"install null"**, você colou a URL errada na opção errada. A opção **"From a URL"** espera o **`packageManifest.json`**. O `repository.json` só serve para a opção **"Add a Custom Repository" → "Browse by Tags"**.

## Qual driver para quê

| Driver | Para |
|---|---|
| **HubZigbee** | o gateway (device de LAN) — você cria este |
| **HubZigbee Module** | cada aparelho Zigbee — criado sozinho pelo gateway |

## Configuração

1. Em **Devices** → **Add Device** → **Virtual**, escolha o driver **HubZigbee** e dê um nome.
2. Nas **Preferences**, preencha o **IP do gateway** e clique em **Save Preferences**.
3. Os aparelhos já pareados aparecem sozinhos, cada um com seus filhos nativos.
   Para parear um aparelho novo, use o comando **`parear`** (janela padrão de 180 s).

## Os atributos que dizem se está tudo bem

- **`presence`** — `present` quando o gateway está respondendo.
- **`ultimoContato`** — data e hora da última resposta do gateway.
- **`devices`** — quantos aparelhos Zigbee o gateway conhece.

## Suporte
TecnoSimples Tecnologia LTDA · contato@tecnosimples.com.br · (14) 99760-6885
