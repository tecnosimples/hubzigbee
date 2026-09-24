/**
 * HubZigbee
 *
 * TecnoSimples Tecnologia LTDA
 * contato@tecnosimples.com.br | (14) 99760-6885
 * (c) 2026 TecnoSimples - Todos os direitos reservados.
 *
 * Produto licenciado. Distribuido via Hubitat Package Manager.
 * Versao do pacote: 1.0.0
 */
import groovy.json.JsonSlurper
import groovy.transform.Field
@Field static final String VERSAO = "2.0.1"
@Field static final Integer PORTA_HUB = 39501
metadata {
definition(name: "HubZigbee", namespace: "tecnosimples", author: "TecnoSimples") {
capability "Initialize"
capability "Refresh"
capability "PresenceSensor"
attribute "gatewayIp", "string"
attribute "rssi", "number"
attribute "uptime", "number"
attribute "canalZigbee", "number"
attribute "devices", "number"
attribute "ultimoContato", "string"
command "definirIp", [[name: "IP", type: "STRING", description: "IP do gateway, para configurar sem abrir a página"]]
command "parear", [[name: "Duração (s)", type: "NUMBER", description: "Janela de pareamento, padrão 180"]]
command "sincronizar"
command "acionar", [[name: "IEEE", type: "STRING"], [name: "Endpoint", type: "NUMBER"],
[name: "Ação", type: "STRING", description: "on, off ou toggle"]]
command "removerDevice", [[name: "IEEE", type: "STRING", description: "IEEE de 16 dígitos do device a expulsar"]]
}
preferences {
input name: "ip", type: "text", title: "IP do gateway", description: "Ex.: 10.0.1.160", required: true
input name: "intervaloSaude", type: "enum", title: "Checagem de saúde",
options: [["1": "1 minuto"], ["5": "5 minutos"], ["15": "15 minutos"]], defaultValue: "5"
input name: "criarFilhos", type: "bool", title: "Criar módulos e canais automaticamente", defaultValue: true
input name: "logDetalhado", type: "bool", title: "Log detalhado (desliga sozinho em 30 min)", defaultValue: true
}
}
private String alvo() {
return (ip ?: device.getDataValue("ip") ?: state.ipManual)
}
private boolean devoCriarFilhos() {
return (criarFilhos == null) ? true : (criarFilhos as boolean)
}
private boolean logando() {
return (logDetalhado == null) ? false : (logDetalhado as boolean)
}
private void aprenderIp(String hex) {
if (!(hex ==~ /[0-9A-Fa-f]{8}/)) {
return
}
final String origem = (0..3).collect { Integer.parseInt(hex.substring(it * 2, it * 2 + 2), 16) }.join(".")
final String atual = alvo()?.trim()
if (origem == atual) {
return
}
log.warn "Gateway mudou de IP: ${atual ?: '(nenhum)'} -> ${origem}. Corrigindo."
device.updateSetting("ip", [value: origem, type: "text"])
device.updateDataValue("ip", origem)
state.ipManual = origem
sendEvent(name: "gatewayIp", value: origem)
}
def definirIp(String valor) {
if (!valor?.trim()) {
log.warn "IP vazio"
return
}
state.ipManual = valor.trim()
log.info "IP do gateway definido por comando: ${state.ipManual}"
initialize()
}
def installed() {
log.info "HubZigbee ${VERSAO} instalado"
initialize()
}
def updated() {
log.info "HubZigbee: preferências salvas"
initialize()
}
def initialize() {
unschedule()
state.versao = VERSAO
if (!alvo()) {
log.warn "Sem IP do gateway — preencha a preferência ou use o comando definirIp"
return
}
consultarInfo(true)
final Integer minutos = (intervaloSaude ?: "5") as Integer
if (minutos == 1) {
runEvery1Minute("checarSaude")
} else if (minutos == 15) {
runEvery15Minutes("checarSaude")
} else {
runEvery5Minutes("checarSaude")
}
if (logando()) {
runIn(1800, "desligarLogDetalhado")
}
}
def desligarLogDetalhado() {
log.info "Log detalhado desligado automaticamente"
device.updateSetting("logDetalhado", [value: "false", type: "bool"])
}
def refresh() {
consultarInfo(false)
sincronizar()
}
def parse(String description) {
final Map msg = parseLanMessage(description)
if (!msg?.body) {
return
}
Map dados
try {
dados = new JsonSlurper().parseText(msg.body) as Map
} catch (Exception e) {
log.warn "Corpo não é JSON: ${msg.body}"
return
}
marcarVivo()
aprenderIp(msg.ip as String) 
switch (dados.t) {
case "ev":
tratarEvento(dados)
break
case "saude":
if (dados.rssi != null) sendEvent(name: "rssi", value: dados.rssi as Integer, unit: "dBm")
if (dados.uptime != null) sendEvent(name: "uptime", value: dados.uptime as Integer, unit: "s")
if (dados.canal != null) sendEvent(name: "canalZigbee", value: dados.canal as Integer)
if (logando()) log.debug "Batimento do gateway: ${dados}"
break
case "dev":
log.info "Device novo na rede Zigbee: ${dados.ieee} (${dados.fab} ${dados.mod})"
runIn(3, "sincronizar")
break
default:
if (logando()) log.debug "Mensagem não reconhecida: ${dados}"
}
}
private void tratarEvento(Map dados) {
final String ieee = dados.ieee
def modulo = getChildDevice(ieee)
if (!modulo) {
if (devoCriarFilhos()) {
if (logando()) log.debug "Evento de ${ieee} sem módulo — sincronizando"
runIn(2, "sincronizar")
}
return
}
modulo.tratarEventoDoGateway(dados.ep, dados.a as String, dados.v)
}
def enviarComando(String ieee, Integer ep, String tipo, Map extras = [:]) {
Map query = [ieee: ieee, ep: ep, tipo: tipo] + extras
chamar("/api/cmd", query)
}
def acionar(String ieee, valor, String acao) {
final Integer ep = (valor ?: 1) as Integer
final String a = acao?.trim()?.toLowerCase()
if (!(a in ["on", "off", "toggle"])) {
log.warn "Ação inválida: ${acao} (use on, off ou toggle)"
return
}
log.info "Acionando ${ieee} ep ${ep}: ${a}"
enviarComando(ieee, ep, "onoff", [valor: a])
}
def atualizarDevice(String ieee) {
chamar("/api/atualizar", [ieee: ieee])
}
def parear(valor = null) {
final Integer dur = (valor ?: 180) as Integer
log.info "Abrindo janela de pareamento por ${dur}s"
chamar("/api/parear", [dur: dur])
}
def removerDevice(String ieee) {
if (!ieee || ieee.length() != 16) {
log.warn "IEEE inválido: ${ieee}"
return
}
log.warn "Expulsando ${ieee} da rede Zigbee"
chamar("/api/remover", [ieee: ieee])
def modulo = getChildDevice(ieee)
if (modulo) {
modulo.apagarCanais()
runIn(3, "apagarModulo", [data: [ieee: ieee]])
}
}
def apagarModulo(Map dados) {
try {
deleteChildDevice(dados.ieee as String)
log.info "Módulo ${dados.ieee} removido da Hubitat"
} catch (Exception e) {
log.error "Não consegui remover o módulo ${dados.ieee}: ${e.message}"
}
}
private void chamar(String caminho, Map query = [:]) {
if (!alvo()) {
log.warn "Sem IP do gateway"
return
}
final Map params = [uri: "http://${alvo()}", path: caminho, query: query, timeout: 10]
if (logando()) log.debug "HTTP ${caminho} ${query}"
asynchttpGet("respostaGenerica", params, [caminho: caminho])
}
def respostaGenerica(resposta, Map dados) {
if (resposta.status != 200) {
log.warn "${dados.caminho} devolveu ${resposta.status}"
return
}
marcarVivo()
if (logando()) log.debug "${dados.caminho} ok"
}
private void consultarInfo(boolean ajustarDni) {
asynchttpGet("respostaInfo", [uri: "http://${alvo()}", path: "/api/info", timeout: 10], [ajustarDni: ajustarDni])
}
def respostaInfo(resposta, Map dados) {
if (resposta.status != 200) {
marcarMorto("HTTP ${resposta.status} em /api/info")
return
}
Map info
try {
info = new JsonSlurper().parseText(resposta.data) as Map
} catch (Exception e) {
marcarMorto("resposta de /api/info não é JSON")
return
}
marcarVivo()
sendEvent(name: "gatewayIp", value: info.ip)
sendEvent(name: "rssi", value: (info.rssi ?: 0) as Integer, unit: "dBm")
sendEvent(name: "uptime", value: (info.uptime ?: 0) as Integer, unit: "s")
sendEvent(name: "canalZigbee", value: (info.canal ?: 0) as Integer)
sendEvent(name: "devices", value: (info.devices ?: 0) as Integer)
state.firmware = "${info.fw} ${info.ver}"
state.pan = info.pan
final String mac = info.mac?.replaceAll(":", "")?.toUpperCase()
if (dados.ajustarDni && mac && device.deviceNetworkId != mac) {
log.info "Ajustando DNI para o MAC do gateway: ${mac}"
device.deviceNetworkId = mac
}
chamar("/api/assinar", [porta: PORTA_HUB])
if (dados.ajustarDni) {
runIn(4, "sincronizar")
}
}
def sincronizar() {
if (!alvo()) {
return
}
asynchttpGet("respostaDevices", [uri: "http://${alvo()}", path: "/api/devices", timeout: 15], null)
}
def respostaDevices(resposta, Map dados) {
if (resposta.status != 200) {
log.warn "/api/devices devolveu ${resposta.status}"
return
}
List lista
try {
lista = new JsonSlurper().parseText(resposta.data) as List
} catch (Exception e) {
log.warn "/api/devices não devolveu JSON"
return
}
marcarVivo()
if (!devoCriarFilhos()) {
return
}
migrarParaTresNiveis()
Integer criados = 0
lista.each { Map dev ->
final String ieee = dev.ieee as String
if (!ieee) {
return
}
def modulo = getChildDevice(ieee)
if (!modulo) {
final String sufixo = (ieee.length() >= 4) ? ieee.substring(ieee.length() - 4) : ieee
final String rotulo = "${dev.mod ?: 'Zigbee'} ${sufixo}"
try {
modulo = addChildDevice("tecnosimples", "HubZigbee Module", ieee,
[name: "HubZigbee Module", label: rotulo,
isComponent: false])
criados++
log.info "Módulo criado: ${rotulo} (${ieee})"
} catch (Exception e) {
log.error "Falha ao criar módulo ${ieee}: ${e.message}"
return
}
}
modulo.configurar(dev)
}
if (criados > 0) {
lista.each { Map dev -> atualizarDevice(dev.ieee as String) }
}
state.ultimaSincronia = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}
private void migrarParaTresNiveis() {
if (state.migrado3niveis) {
return
}
getChildDevices()?.each { antigo ->
if (antigo.deviceNetworkId?.contains("-")) {
log.warn "Estrutura nova: removendo canal solto ${antigo.deviceNetworkId} (será recriado sob o módulo)"
try {
deleteChildDevice(antigo.deviceNetworkId)
} catch (Exception e) {
log.error "Não consegui remover ${antigo.deviceNetworkId}: ${e.message}"
}
}
}
state.migrado3niveis = true
}
def checarSaude() {
consultarInfo(false)
final Long ultimo = (state.ultimoContatoMs ?: 0L) as Long
final Long limite = ((intervaloSaude ?: "5") as Long) * 60000L * 3L
if (ultimo > 0 && (now() - ultimo) > limite) {
marcarMorto("sem contato há ${((now() - ultimo) / 60000L) as Integer} min")
}
}
private void marcarVivo() {
state.ultimoContatoMs = now()
sendEvent(name: "ultimoContato", value: new Date().format("HH:mm:ss", location.timeZone))
if (device.currentValue("presence") != "present") {
sendEvent(name: "presence", value: "present", descriptionText: "gateway respondendo")
log.info "Gateway presente"
}
}
private void marcarMorto(String motivo) {
if (device.currentValue("presence") != "not present") {
sendEvent(name: "presence", value: "not present", descriptionText: motivo)
log.warn "Gateway ausente: ${motivo}"
}
}
