/**
 * HubZigbee Module
 *
 * TecnoSimples Tecnologia LTDA
 * contato@tecnosimples.com.br | (14) 99760-6885
 * (c) 2026 TecnoSimples - Todos os direitos reservados.
 *
 * Produto licenciado. Distribuido via Hubitat Package Manager.
 * Versao do pacote: 1.0.0
 */
import groovy.transform.Field
metadata {
definition(name: "HubZigbee Module", namespace: "tecnosimples", author: "TecnoSimples") {
capability "Refresh"
capability "Actuator"
capability "PresenceSensor"
attribute "ieee", "string"
attribute "fabricante", "string"
attribute "modelo", "string"
attribute "canais", "number"
attribute "preparado", "string"
attribute "ultimoContato", "string"
attribute "homologacao", "string"
command "recriarCanais"
command "remover"
command "aplicarHomologacao"
command "testarClassificador"
}
preferences {
input name: "silencioMax", type: "enum", title: "Considerar ausente após",
options: [["600": "10 minutos"], ["3600": "1 hora"], ["21600": "6 horas"], ["0": "nunca"]],
defaultValue: "3600"
input name: "logDetalhado", type: "bool", title: "Log detalhado", defaultValue: false
}
}
@Field static final List DEVID_CONTROLE = [0x0000, 0x0001, 0x0006, 0x0103, 0x0104, 0x0105, 0x0820]
@Field static final Map ZONA_FUNCAO = [21: "contato", 13: "movimento", 42: "vazamento", 40: "fumaca"]
@Field static final Map ZONA_ATRIBUTO = [contato: "contact", movimento: "motion",
vazamento: "water", fumaca: "smoke"]
@Field static final Map ZONA_VALOR = [contact: ["closed", "open"], motion: ["inactive", "active"],
water: ["dry", "wet"], smoke: ["clear", "detected"]]
@Field static final Map DRIVER_NATIVO = [
interruptor: "Generic Component Switch",
dimmer: "Generic Component Dimmer",
botoes: "Generic Component Button Controller",
contato: "Generic Component Contact Sensor",
movimento: "Generic Component Motion Sensor",
vazamento: "Generic Component Water Sensor",
fumaca: "Generic Component Smoke Detector",
tempUmidade: "Generic Component Temperature Humidity Sensor",
luz: "Component Illuminance Sensor",
presenca: "Generic Component Presence Sensor",
energia: "Generic Component Electric Sensor"
]
@Field static final Map CATALOGO = [
"_TZ3000_8yhypbo7|TS0203": [
status: "homologado", fonte: "bancada", data: "2026-09-22",
funcoes: [
[tipo: "contato", rotulo: "Contato", chave: "e1",
entradas: ["contact": "e1-c1280"], conversao: [bit: 0]]
]
]
]
private boolean logando() {
return (logDetalhado == null) ? false : (logDetalhado as boolean)
}
def installed() {
sendEvent(name: "ieee", value: device.deviceNetworkId)
refresh()
}
def updated() {
sendEvent(name: "ieee", value: device.deviceNetworkId)
}
def refresh() {
parent.atualizarDevice(device.deviceNetworkId)
}
def configurar(Map dev) {
sendEvent(name: "ieee", value: device.deviceNetworkId)
sendEvent(name: "fabricante", value: (dev.fab ?: "?").toString())
sendEvent(name: "modelo", value: (dev.mod ?: "?").toString())
sendEvent(name: "preparado", value: dev.prep ? "sim" : "nao")
state.tuya = (dev.tuya == true)
state.bateria = (dev.containsKey("bateria")) ? (dev.bateria == true) : null
avaliarSilencio((dev.visto ?: 0) as Long)
if (dev.desc == false) {
if (logando()) log.debug "ficha incompleta — classificação adiada"
return
}
if (!(dev.fab) || !(dev.mod)) {
if (logando()) log.debug "sem fabricante/modelo — mantém o que existe"
return
}
final Map r = classificar(dev, fichaDoCatalogo(dev.fab as String, dev.mod as String))
aplicarClassificacao(r)
}
private void aplicarClassificacao(Map r) {
final List dnisNovos = r.funcoes*.dni
final List conflitos = (getChildDevices() ?: []).findAll { !(it.deviceNetworkId in dnisNovos) }*.deviceNetworkId
if (conflitos) {
state.trocaPendente = [dnis: conflitos, proposta: r]
sendEvent(name: "homologacao", value: "revisao pendente")
log.warn "${device.displayName}: mudança de função pendente (${conflitos.join(', ')}). " +
"Confira 'In use by' na página de cada filho e use aplicarHomologacao para aplicar."
return
}
state.rotas = r.rotas
state.mapaBotoes = r.mapaBotoes
sendEvent(name: "canais", value: r.funcoes.count { it.tipo == "interruptor" })
sendEvent(name: "homologacao", value: r.status)
r.funcoes.each { Map f -> criarFilho(f) }
}
private void criarFilho(Map f) {
if (getChildDevice(f.dni)) { return }
final String driver = DRIVER_NATIVO[f.tipo]
if (!driver) { log.error "sem driver nativo para ${f.tipo}"; return }
try {
def filho = addChildDevice("hubitat", driver, f.dni,
[name: driver, label: "${device.displayName} ${f.rotulo}",
isComponent: false])
filho.updateDataValue("endpoint", (f.ep ?: 1).toString())
if (f.botoes) { filho.sendEvent(name: "numberOfButtons", value: f.botoes) }
log.info "Filho criado: ${f.rotulo} (${f.dni}, ${driver})"
} catch (Exception e) {
log.error "Falha ao criar ${f.dni}: ${e.message}"
}
}
def tratarEventoDoGateway(ep, String atributo, def valor) {
marcarVivo()
final Map rota = acharRota(ep, atributo)
if (!rota) {
state[atributo] = valor
return
}
def filho = getChildDevice(rota.dni)
if (!filho) { state[atributo] = valor; return }
final String nome = rota.atributo ?: atributo
if (nome == "pushed" || nome == "doubleTapped" || nome == "held") {
final Integer botao = (state.mapaBotoes ?: [:])[chaveEp(ep)] as Integer
if (!botao) { state[atributo] = valor; return }
filho.parse([[name: nome, value: botao, isStateChange: true,
descriptionText: "${filho.displayName} botão ${botao}"]])
return
}
final def v = ZONA_VALOR[nome] ? ZONA_VALOR[nome][(valor as Integer) & 1] : valor
filho.parse([[name: nome, value: v, descriptionText: "${filho.displayName} ${nome} ${v}"]])
}
private Map acharRota(ep, String atributo) {
final Map rotas = (state.rotas ?: [:])
final String chaveMedicao = chaveRota(ep, 2820)
if (atributo == "voltage" && rotas[chaveMedicao]) {
return [dni: rotas[chaveMedicao][0], atributo: null]
}
final List chaves = []
if (atributo?.startsWith("dp")) { chaves << ("d" + atributo.substring(2)) }
chaves << chaveRota(ep, clusterDoAtributo(atributo))
for (String c : chaves) {
if (rotas[c]) { return [dni: rotas[c][0], atributo: rotas[c][1]] }
}
return null
}
private Integer clusterDoAtributo(String a) {
switch (a) {
case "switch": case "pushed": case "doubleTapped": case "held": return 6
case "level": return 8
case "battery": case "voltage": return 1
case "temperature": return 1026
case "humidity": return 1029
case "illuminance": return 1024
case "occupancy": return 1030
case "zone": return 1280
case "power": return 2820
case "energy": return 1794
default: return -1
}
}
Map classificar(Map dev, Map ficha) {
if (ficha) { return classificarPorCatalogo(dev, ficha) }
final String ieee = dev.ieee as String
List funcoes = []
Map rotas = [:]
Map mapaBotoes = [:]
List epsBotao = []
List epsMedidor = []
(dev.eps ?: []).each { Map e ->
final Integer ep = (e.ep ?: 0) as Integer
if (ep == 0 || ep == 242) { return }
final List entrada = (e.in ?: []) as List
final List saida = (e.out ?: []) as List
final Integer devId = (e.devId ?: 0) as Integer
final boolean ehControle = DEVID_CONTROLE.contains(devId)
if (entrada.contains(1280) || devId == 0x0402) {
final String funcao = ZONA_FUNCAO[(dev.zona ?: -1) as Integer]
if (funcao) {
final String dni = dniDe(ieee, funcao, ep)
funcoes << [tipo: funcao, rotulo: funcao.capitalize(), dni: dni, ep: ep]
rotas[chaveRota(ep, 1280)] = [dni, ZONA_ATRIBUTO[funcao]]
}
} else if (ehControle || (saida.contains(6) && !entrada.contains(6))) {
epsBotao << ep
} else if (entrada.contains(6) && entrada.contains(8)) {
final String dni = dniDe(ieee, "dimmer", ep)
funcoes << [tipo: "dimmer", rotulo: "Dimmer", dni: dni, ep: ep]
rotas[chaveRota(ep, 6)] = [dni, null]
rotas[chaveRota(ep, 8)] = [dni, null]
} else if (entrada.contains(6)) {
final String dni = dniDe(ieee, "interruptor", ep)
funcoes << [tipo: "interruptor", rotulo: "canal ${ep}", dni: dni, ep: ep]
rotas[chaveRota(ep, 6)] = [dni, null]
}
if (entrada.contains(1026) || entrada.contains(1029)) {
final String dni = dniDe(ieee, "tempumidade", ep)
funcoes << [tipo: "tempUmidade", rotulo: "Temperatura", dni: dni, ep: ep]
rotas[chaveRota(ep, 1026)] = [dni, null]
rotas[chaveRota(ep, 1029)] = [dni, null]
}
if (entrada.contains(1024)) {
final String dni = dniDe(ieee, "luz", ep)
funcoes << [tipo: "luz", rotulo: "Luminosidade", dni: dni, ep: ep]
rotas[chaveRota(ep, 1024)] = [dni, null]
}
if (entrada.contains(1030)) {
final String dni = dniDe(ieee, "presenca", ep)
funcoes << [tipo: "presenca", rotulo: "Presenca", dni: dni, ep: ep]
rotas[chaveRota(ep, 1030)] = [dni, null]
}
if (entrada.contains(1794) || entrada.contains(2820)) { epsMedidor << ep }
}
if (epsBotao) {
final String dni = dniDe(ieee, "botoes", null)
funcoes << [tipo: "botoes", rotulo: "Botoes", dni: dni, ep: epsBotao.first(),
botoes: epsBotao.size()]
epsBotao.eachWithIndex { Integer ep, Integer i ->
mapaBotoes[chaveEp(ep)] = i + 1
rotas[chaveRota(ep, 6)] = [dni, null]
}
}
if (epsMedidor.size() == 1) {
final Integer ep = epsMedidor.first() as Integer
final String dni = dniDe(ieee, "energia", null)
funcoes << [tipo: "energia", rotulo: "Energia", dni: dni, ep: ep]
rotas[chaveRota(ep, 1794)] = [dni, null]
rotas[chaveRota(ep, 2820)] = [dni, null]
} else {
epsMedidor.each { Integer ep ->
final String dni = dniDe(ieee, "energia", ep)
funcoes << [tipo: "energia", rotulo: "Energia ${ep}", dni: dni, ep: ep]
rotas[chaveRota(ep, 1794)] = [dni, null]
rotas[chaveRota(ep, 2820)] = [dni, null]
}
}
final String status = funcoes.isEmpty() ? "aguardando" : "nao homologado"
return [funcoes: funcoes, rotas: rotas, mapaBotoes: mapaBotoes, status: status]
}
private Map fichaDoCatalogo(String fab, String mod) {
if (!fab || !mod) { return null }
final Map direto = CATALOGO["${fab}|${mod}".toString()]
if (direto) { return direto }
final String sufixo = "|${mod}".toString()
return CATALOGO.find { k, v -> v.chaveModelo == true && k.endsWith(sufixo) }?.value
}
private Map classificarPorCatalogo(Map dev, Map ficha) {
final String ieee = dev.ieee as String
List funcoes = []
Map rotas = [:]
Map mapaBotoes = [:]
(ficha.funcoes ?: []).each { Map f ->
final String chave = f.chave as String
final Integer ep = (chave?.startsWith("e")) ? (chave.substring(1) as Integer) : null
final String dni = chave ? "${ieee}-${f.tipo}-${chave}".toString() : dniDe(ieee, f.tipo as String, null)
final Map funcao = [tipo: f.tipo, rotulo: f.rotulo, dni: dni, ep: (ep ?: (f.ep ?: 1))]
if (f.tipo == "botoes") {
(f.botoes ?: [:]).each { String c, Integer n -> mapaBotoes[c.toString()] = n }
funcao.botoes = mapaBotoes.size()
}
funcoes << funcao
(f.entradas ?: [:]).each { String atributo, String origem -> rotas[origem.toString()] = [dni, atributo] }
}
return [funcoes: funcoes, rotas: rotas, mapaBotoes: mapaBotoes, status: "homologado"]
}
private String chaveRota(ep, Integer cluster) {
return "e${ep}-c${cluster}".toString()
}
private String chaveEp(ep) {
return "e${ep}".toString()
}
private String dniDe(String ieee, String tipo, Integer ep) {
return (ep == null) ? "${ieee}-${tipo}".toString() : "${ieee}-${tipo}-e${ep}".toString()
}
def componentOn(cd) { comandarFilho(cd, "onoff", [valor: "on"]) }
def componentOff(cd) { comandarFilho(cd, "onoff", [valor: "off"]) }
def componentSetLevel(cd, nivel, duracao = null) {
comandarFilho(cd, "nivel", [valor: nivel as Integer, t: (duracao ?: 0) as Integer])
}
def componentRefresh(cd) { parent.atualizarDevice(device.deviceNetworkId) }
def componentPush(cd, botao) { cd.parse([[name: "pushed", value: botao, isStateChange: true]]) }
def componentDoubleTap(cd, botao) { cd.parse([[name: "doubleTapped", value: botao, isStateChange: true]]) }
def componentHold(cd, botao) { cd.parse([[name: "held", value: botao, isStateChange: true]]) }
private void comandarFilho(cd, String tipo, Map extras) {
final String ep = cd?.getDataValue("endpoint")
if (!ep) { log.error "filho ${cd?.deviceNetworkId} sem endpoint gravado"; return }
parent.enviarComando(device.deviceNetworkId, ep as Integer, tipo, extras)
}
def aplicarHomologacao() {
final Map pend = state.trocaPendente
if (!pend) { log.info "${device.displayName}: nada pendente"; return }
log.warn "${device.displayName}: aplicando troca de função — ${pend.dnis.join(', ')} serão substituídos"
List falhas = []
pend.dnis.each { String dni ->
try {
deleteChildDevice(dni)
} catch (Exception e) {
falhas << dni
log.error "não consegui remover ${dni}: ${e.message}"
}
}
if (falhas) {
sendEvent(name: "homologacao", value: "filho em uso")
log.warn "${device.displayName}: troca interrompida — ${falhas.join(', ')} em uso por algum app. " +
"Abra a página de cada filho, veja 'In use by', desvincule e use aplicarHomologacao de novo."
return
}
state.remove("trocaPendente")
aplicarClassificacao(pend.proposta)
}
def enviarComando(Integer ep, String tipo, Map extras = [:]) {
parent.enviarComando(device.deviceNetworkId, ep, tipo, extras)
}
def atualizarModulo() {
parent.atualizarDevice(device.deviceNetworkId)
}
def recriarCanais() {
log.info "Recriando canais de ${device.displayName}"
getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
["eps", "rotas", "mapaBotoes", "trocaPendente"].each { state.remove(it) }
parent.sincronizar()
}
def remover() {
log.warn "Expulsando ${device.deviceNetworkId} da rede Zigbee"
parent.removerDevice(device.deviceNetworkId)
}
def apagarCanais() {
getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) }
}
private void marcarVivo() {
state.ultimoContatoMs = now()
sendEvent(name: "ultimoContato", value: new Date().format("HH:mm:ss", location.timeZone))
if (device.currentValue("presence") != "present") {
sendEvent(name: "presence", value: "present", descriptionText: "módulo respondendo")
}
}
private void avaliarSilencio(Long visto) {
final String padrao = (state.bateria == false) ? "3600" : "21600"
final Long limite = (silencioMax ?: padrao) as Long
if (limite == 0L) {
sendEvent(name: "presence", value: "present")
return
}
if (visto <= limite) {
marcarVivo()
} else if (device.currentValue("presence") != "not present") {
sendEvent(name: "presence", value: "not present",
descriptionText: "sem sinal há ${(visto / 60L) as Integer} min")
log.warn "${device.displayName} ausente: sem sinal há ${(visto / 60L) as Integer} min"
}
}
private List casosDeTeste() {
return [
[nome: "TS0014 4 reles",
dev: [ieee: "70AC08FFFE6BD851", fab: "_TZ3000_mrduubod", mod: "TS0014", eps: [
[ep: 1, devId: 0x0100, in: [0,3,4,5,6], out: [25,10]],
[ep: 2, devId: 0x0100, in: [3,4,5,6], out: []],
[ep: 3, devId: 0x0100, in: [3,4,5,6], out: []],
[ep: 4, devId: 0x0100, in: [3,4,5,6], out: []],
[ep: 242, devId: 0x61, in: [], out: [33]]]],
espera: ["interruptor-e1", "interruptor-e2", "interruptor-e3", "interruptor-e4"]],
[nome: "TS0003 3 reles + medicao",
dev: [ieee: "A4C1389ECD5F9126", fab: "_TZ3000_78fgsn7i", mod: "TS0003", eps: [
[ep: 1, devId: 0x0100, in: [3,4,5,6,1794,2820,0], out: [25,10]],
[ep: 2, devId: 0x0100, in: [4,5,6], out: []],
[ep: 3, devId: 0x0100, in: [4,5,6], out: []],
[ep: 242, devId: 0x61, in: [], out: [33]]]],
espera: ["interruptor-e1", "interruptor-e2", "interruptor-e3", "energia"]],
[nome: "TS0044 teclado",
dev: [ieee: "8C65A3FFFEB369B4", fab: "_TZ3000_wkai4ga5", mod: "TS0044", eps: [
[ep: 1, devId: 0x0000, in: [], out: []],
[ep: 2, devId: 0x0000, in: [1,6], out: []],
[ep: 3, devId: 0x0000, in: [1,6], out: []],
[ep: 4, devId: 0x0000, in: [1,6], out: []]]],
espera: ["botoes"]],
[nome: "TS0203 contato",
dev: [ieee: "A4C1385D71FD3FB1", fab: "_TZ3000_8yhypbo7", mod: "TS0203", zona: 21, eps: [
[ep: 1, devId: 0x0402, in: [1,3,1280,0], out: [3,4,5,6,8,4096,25,10]]]],
espera: ["contato-e1"]],
[nome: "TS0203 sem zone type",
dev: [ieee: "A4C1385D71FD3FB1", fab: "_TZ3000_8yhypbo7", mod: "TS0203", eps: [
[ep: 1, devId: 0x0402, in: [1,3,1280,0], out: []]]],
espera: []],
[nome: "tecla que chega depois",
dev: [ieee: "8C65A3FFFEB369B4", fab: "_TZ3000_wkai4ga5", mod: "TS0044", eps: [
[ep: 2, devId: 0x0000, in: [1,6], out: []],
[ep: 3, devId: 0x0000, in: [1,6], out: []],
[ep: 4, devId: 0x0000, in: [1,6], out: []],
[ep: 1, devId: 0x0000, in: [1,6], out: []]]],
espera: ["botoes"]],
[nome: "endpoint misto: rele + medicao",
dev: [ieee: "0000000000000001", fab: "_TZ3000_teste", mod: "TSTEST", eps: [
[ep: 1, devId: 0x0100, in: [6, 1794], out: []]]],
espera: ["interruptor-e1", "energia"]],
[nome: "TS0203 pelo catalogo, sem zone type",
dev: [ieee: "A4C1385D71FD3FB1", fab: "_TZ3000_8yhypbo7", mod: "TS0203", eps: [
[ep: 1, devId: 0x0402, in: [1,3,1280,0], out: []]]],
ficha: CATALOGO["_TZ3000_8yhypbo7|TS0203"],
espera: ["contato-e1"], status: "homologado"]
]
}
def testarClassificador() {
Integer falhas = 0
casosDeTeste().each { Map caso ->
final Map r = classificar(caso.dev, caso.ficha)
final List obtido = (r.funcoes*.dni).collect { it.tokenize("-").drop(1).join("-") }.sort()
final List espera = (caso.espera as List).sort()
if (caso.status && r.status != caso.status) {
falhas++
log.error "FAIL ${caso.nome}: status esperado ${caso.status}, veio ${r.status}"
} else if (obtido == espera) {
log.info "PASS ${caso.nome}: ${obtido}"
} else {
falhas++
log.error "FAIL ${caso.nome}: esperava ${espera}, veio ${obtido}"
}
}
final Map tardio = classificar(casosDeTeste().find { it.nome == "tecla que chega depois" }.dev, null)
if (tardio.mapaBotoes["e2"] != 1 || tardio.mapaBotoes["e1"] != 4) {
falhas++
log.error "FAIL mapaBotoes renumerou: ${tardio.mapaBotoes}"
} else {
log.info "PASS mapaBotoes estavel: ${tardio.mapaBotoes}"
}
final Map ts0003 = classificar(casosDeTeste().find { it.nome.startsWith("TS0003") }.dev, null)
if (ts0003.rotas["e1-c6"]?.getAt(0) == "A4C1389ECD5F9126-interruptor-e1" &&
ts0003.rotas["e1-c1794"]?.getAt(0) == "A4C1389ECD5F9126-energia") {
log.info "PASS rota por cluster no endpoint misto"
} else {
falhas++
log.error "FAIL rota por cluster: ${ts0003.rotas}"
}
final Map cat = casosDeTeste().find { it.ficha }
final List rotaCat = classificar(cat.dev, cat.ficha).rotas["e1-c1280"]
if (rotaCat == ["A4C1385D71FD3FB1-contato-e1", "contact"]) {
log.info "PASS rota do catalogo: zone -> contact"
} else {
falhas++
log.error "FAIL rota do catalogo: ${rotaCat}"
}
log.info falhas == 0 ? "testarClassificador: TUDO PASSOU" : "testarClassificador: ${falhas} FALHA(S)"
}
