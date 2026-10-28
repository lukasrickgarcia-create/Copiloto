# Copiloto

App Android para o carro: alerta de radar por voz em segundo plano (liga sozinho quando detecta que você está de carro), consulta de placa e tabela FIPE.

## Baixar o APK

Cada alteração enviada para a branch `main` gera um APK novo automaticamente (aba **Actions**).

Link fixo da última versão:
https://github.com/lukasrickgarcia-create/copiloto/releases/download/apk/copiloto.apk

## Primeiro uso

1. Instale o APK (o Android pede para permitir "instalar apps desconhecidos" do navegador).
2. Abra o app e libere as permissões que ele pedir:
   - Localização → **Permitir o tempo todo**
   - Atividade física
   - Notificações
   - Sem restrição de bateria
3. Pronto: ao começar a dirigir, o radar liga sozinho e desliga 5 min depois que você estaciona.

## Bases de radares

- ANTT (rodovias federais concedidas) — dados abertos
- OpenStreetMap (cidades e estradas) — baixado automaticamente para a região onde você está
- Radares marcados por você (botão no app ou na notificação)
- Importação de CSV/GPX de outras bases
