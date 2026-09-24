# HubZigbee — Hubitat (TecnoSimples)

Gateway Zigbee satélite para Hubitat: os aparelhos pareados no HubZigbee chegam
ao hub como devices nativos (interruptor, teclas, contato, energia).
**Produto licenciado da TecnoSimples Tecnologia LTDA.** Requer o hardware HubZigbee.

## Instalação (via Hubitat Package Manager)

1. No HPM, escolha **Install** → **From a URL**.
2. Cole a URL do **packageManifest.json** (atenção: é o manifesto, NÃO o repository.json):
   `https://raw.githubusercontent.com/tecnosimples/hubzigbee/main/packageManifest.json`
3. Avance — deve aparecer **"You are about to install HubZigbee"**. Conclua (o HPM baixa os 2 drivers).

> Cole a URL **sem espaço antes do `https`**. Com espaço, o HPM responde **"Invalid Package File"**.

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

## Remover um aparelho

1. Abra o device do aparelho (o **HubZigbee Module**) e use o comando **`remover`**.
2. O aparelho sai da rede Zigbee **e** da Hubitat: os filhos e o Module são apagados sozinhos.

- **Não apague o Module pelo "Remove Device" da Hubitat.** O aparelho continua pareado no
  HubZigbee e volta sozinho na próxima sincronia.
- **Aparelho em uso por regra ou app:** tire-o da regra antes. A Hubitat não apaga device em uso.
- **Aparelho a bateria (botão, sensor):** aperte uma tecla ou acione o sensor logo antes do
  `remover`, para ele estar acordado. Se ele continuar pareado, faça o reset de fábrica nele.
- Aparelho sem Module na Hubitat: use o comando **`removerDevice`** no device do gateway, com o
  IEEE de 16 dígitos.

## Os atributos que dizem se está tudo bem

- **`presence`** — `present` quando o gateway está respondendo.
- **`ultimoContato`** — data e hora da última resposta do gateway.
- **`devices`** — quantos aparelhos Zigbee o gateway conhece.

## Suporte
TecnoSimples Tecnologia LTDA · contato@tecnosimples.com.br · (14) 99760-6885
