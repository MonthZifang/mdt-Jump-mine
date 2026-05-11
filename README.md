# MDT Jump Landmine

`mdt-jump-landmine` 会扫描地图中的 `shockMine` 地雷，并把它们转换成可配置的“跳板点”，用于跳转到其他 Mindustry 服务器、网页地址或自定义 URI。

## 功能概览

- 扫描地图中的 `shockMine`
- 为每个地雷生成独立配置
- 支持跳转到服务器、网页、任意 URI
- 支持手动配置和模板自动分配两种模式
- 支持根据延迟或固定顺序分配目标
- 支持显示服务器名称、简介、延迟、人数、版本等信息

## 工作模式

### 1. `manual`

扫描当前地图后生成完整地雷清单，然后手动为每个地雷填写目标信息。

### 2. `template`

从远程模板接口拉取服务器列表，并按照规则自动把服务器分配到地雷位置。

## 排序方式

### 1. `fixed`

按配置中的 `fixedIndex` 固定排序。

### 2. `latency`

按延迟自动排序。延迟越低，优先级越高，越容易分配到靠近核心的位置。

## 配置目录

首次启动后会自动生成：

```text
config/mods/config/mdt-jump-landmine/plugin-config.json
config/mods/config/mdt-jump-landmine/maps/<map>.json
config/mods/config/mdt-jump-landmine/server-layouts/<map>-servers.json
```

目录说明：

- `plugin-config.json`：插件主配置
- `maps/<map>.json`：地图地雷坐标与基础配置
- `server-layouts/<map>-servers.json`：地雷与服务器的当前绑定结果

插件还会记录地雷下方地板信息，例如：

- `floorBlock`
- `floorCategory`

## `plugin-config.json` 示例

```json
{
  "mode": "template",
  "sortMode": "latency",
  "templateUrl": "https://github.com/Anuken/MindustryServerList/blob/main/servers_v8.json",
  "renderSizeMode": "auto",
  "syncIntervalSeconds": 60,
  "renderRefreshSeconds": 5,
  "labelDurationSeconds": 6.0,
  "renderEnabled": true,
  "defaultServerUriScheme": "mindustry://",
  "coreX": -1,
  "coreY": -1,
  "renderOffsetTiles": 1.35,
  "defaultRenderWidth": 3,
  "defaultRenderHeight": 3,
  "templateProxyEnabled": true,
  "templateProxyType": "http",
  "templateProxyHost": "127.0.0.1",
  "templateProxyPort": 7897,
  "templateConnectTimeoutMillis": 12000,
  "templateReadTimeoutMillis": 12000
}
```

## 模板代理配置

常用字段：

- `templateProxyEnabled`：是否启用代理
- `templateProxyType`：`http` 或 `socks`
- `templateProxyHost`：代理地址
- `templateProxyPort`：代理端口
- `templateConnectTimeoutMillis`：连接超时
- `templateReadTimeoutMillis`：读取超时

示例：

```json
{
  "mode": "template",
  "templateUrl": "https://github.com/Anuken/MindustryServerList/blob/main/servers_v8.json",
  "templateProxyEnabled": true,
  "templateProxyType": "http",
  "templateProxyHost": "127.0.0.1",
  "templateProxyPort": 7897,
  "templateConnectTimeoutMillis": 15000,
  "templateReadTimeoutMillis": 15000
}
```

## 地图配置示例

```json
{
  "mapName": "my-map",
  "generatedAt": 0,
  "mines": [
    {
      "x": 40,
      "y": 32,
      "enabled": true,
      "fixedIndex": 0,
      "displayName": "一区",
      "description": "生存大厅",
      "targetType": "server",
      "targetUrl": "",
      "host": "127.0.0.1",
      "port": 6567,
      "buildLabel": "custom",
      "versionLabel": "157.4",
      "renderWidth": 5,
      "renderHeight": 7
    }
  ]
}
```

说明：

- 网页目标可直接填写 `targetUrl`
- 服务器目标通常填写 `host` 和 `port`
- 自定义协议也可以直接写入完整 `targetUrl`

## 模板接口格式

`templateUrl` 需要返回：

```json
{
  "entries": [
    {
      "enabled": true,
      "fixedIndex": 0,
      "displayName": "一区",
      "description": "生存大厅",
      "targetType": "server",
      "targetUrl": "",
      "host": "127.0.0.1",
      "port": 6567,
      "buildLabel": "custom",
      "versionLabel": "157.4",
      "renderWidth": 5,
      "renderHeight": 7
    }
  ]
}
```

也支持直接使用 Mindustry 官方或公共服务器列表地址，插件会自动处理 GitHub `blob` 链接并转换为可读取的原始内容地址。

## 命令

- `jumpmine-status`
- `jumpmine-reload`
- `jumpmine-rescan`
- `jumpmine-sync`
- `/jumpmine`

## License

- Licensed under `GPL-3.0`.
- Full GPL text is provided in `COPYING`.
- Original author attribution and source notice must be preserved:
  `https://github.com/MonthZifang/mdt-Jump-mine`
- See `NOTICE` and `LICENSE.md` for attribution details.
