-- Episodes panel: a sidebar on the right listing the series' episodes by season.
-- Open with E or the Episodes button. Click / Enter plays, wheel or Up/Down scrolls,
-- Left/Right or the season tabs switch season, Esc or a click on the video closes.

local utils = require "mp.utils"
local ov = mp.create_osd_overlay("ass-events")
local open, season, hover, offset = false, nil, nil, 0
local eps, seasons, history, hits = {}, {}, {}, {}
local show_name = ""

local function norm(p) return (p or ""):gsub("/", "\\"):lower() end

-- history.json is in the player's data folder. The app passes that folder as script-opts qwik-data_dir;
-- without it the same rule as the app applies: %LOCALAPPDATA%\QwikPlayer\Player, or the folder from
-- before the rename (Oranix\Player) when only that one exists.
local function data_file(name)
  local dir = (mp.get_property_native("script-opts") or {})["qwik-data_dir"]
  if not dir or dir == "" then
    local base = os.getenv("LOCALAPPDATA") or ""
    local old = base .. "\\Oranix\\Player"
    dir = base .. "\\QwikPlayer\\Player"
    if not utils.file_info(dir) and utils.file_info(old) then dir = old end
  end
  return dir .. "\\" .. name
end

local function load_history()
  history = {}
  local f = io.open(data_file("history.json"), "r")
  if not f then return end
  local data = utils.parse_json(f:read("*a") or "")
  f:close()
  for k, v in pairs(type(data) == "table" and data or {}) do history[norm(k)] = v end
end

local function parse(file)
  local name = (file or ""):match("([^/\\]+)$") or ""
  name = name:gsub("%.%w+$", ""):gsub("[%._]", " ")
  local pre, s, e, rest = name:match("^(.-)%s*[Ss](%d+)%s*[Ee](%d+)(.*)$")
  if not s then return nil end
  rest = rest:gsub("%f[%w]%d%d%d%d?p%f[%W].*$", ""):gsub("[%[%(].*$", ""):gsub("^[%s%-]+", ""):gsub("[%s%-]+$", "")
  return { show = pre:gsub("[%s%-]+$", ""), season = tonumber(s), episode = tonumber(e), title = rest }
end

local function collect()
  eps, seasons = {}, {}
  local seen = {}
  local pos = mp.get_property_number("playlist-pos", -1)
  for i, it in ipairs(mp.get_property_native("playlist") or {}) do
    local p = parse(it.filename)
    if p then
      p.index, p.path, p.current = i - 1, it.filename, (i - 1) == pos
      table.insert(eps, p)
      if not seen[p.season] then seen[p.season] = true; table.insert(seasons, p.season) end
      if p.current then show_name = p.show; season = season or p.season end
    end
  end
  table.sort(seasons)
  if not season and seasons[1] then season = seasons[1] end
end

local function season_eps()
  local list = {}
  for _, e in ipairs(eps) do if e.season == season then table.insert(list, e) end end
  table.sort(list, function(a, b) return a.episode < b.episode end)
  return list
end

local function rrect(x, y, w, h, r, color, alpha, extra)
  r = math.max(0, math.min(r, math.floor(math.min(w, h) / 2)))
  return string.format("{\\an7\\pos(%d,%d)\\bord0\\shad0%s\\1c&H%s&\\1a&H%s&\\p1}m %d 0 l %d 0 b %d 0 %d 0 %d %d l %d %d b %d %d %d %d %d %d l %d %d b 0 %d 0 %d 0 %d l 0 %d b 0 0 0 0 %d 0{\\p0}",
    x, y, extra or "", color, alpha, r, w - r, w, w, w, r, w, h - r, w, h, w, h, w - r, h, r, h, h, h, h - r, r, r)
end

local function txt(x, y, an, size, color, bold, s, extra)
  s = tostring(s):gsub("{", "("):gsub("}", ")")
  return string.format("{\\an%d\\pos(%d,%d)\\fnPoppins\\fs%d\\b%d\\bord0\\shad0\\1c&H%s&%s}%s", an, x, y, size, bold and 1 or 0, color, extra or "", s)
end

local function fit(s, chars)
  if #s <= chars then return s end
  return s:sub(1, chars - 1):gsub("[\128-\191]*$", ""):gsub("[\192-\255]$", "") .. "…"
end

local function geo()
  local w, h = mp.get_osd_size()
  local s = h / 1080
  local pw = math.floor(math.max(430 * s, math.min(w * 0.34, 620 * s)))
  return { w = w, h = h, s = s, pw = pw, x = w - pw, head = math.floor(190 * s), row = math.floor(112 * s), pad = math.floor(34 * s) }
end

local function visible_rows(g) return math.max(1, math.floor((g.h - g.head - g.pad) / g.row)) end

local function render()
  if not open then ov:remove() return end
  local g = geo()
  local s, out = g.s, {}
  hits = {}
  -- Panel: near-opaque dark sheet with a soft shadow on its left edge.
  table.insert(out, rrect(g.x - math.floor(40 * s), 0, math.floor(60 * s), g.h, 0, "000000", "60", "\\blur" .. math.floor(30 * s)))
  table.insert(out, rrect(g.x, 0, g.pw, g.h, 0, "141414", "0A"))
  local x0 = g.x + g.pad
  table.insert(out, txt(x0, math.floor(52 * s), 4, math.floor(36 * s), "FFFFFF", true, fit(show_name ~= "" and show_name or "Episodes", 28)))
  table.insert(out, txt(g.x + g.pw - g.pad, math.floor(52 * s), 6, math.floor(32 * s), hover == "close" and "FFFFFF" or "9A9A9A", false, "✕"))
  table.insert(hits, { x = g.x + g.pw - g.pad - math.floor(44 * s), y = math.floor(24 * s), w = math.floor(56 * s), h = math.floor(56 * s), id = "close" })

  -- Season tabs
  local tx, ty, th = x0, math.floor(100 * s), math.floor(52 * s)
  for _, sn in ipairs(seasons) do
    local label = #seasons > 4 and ("S" .. sn) or ("Season " .. sn)
    local tw = math.floor((#label * 15 + 40) * s)
    if tx + tw > g.x + g.pw - g.pad then break end
    local active, hv = sn == season, hover == ("season" .. sn)
    table.insert(out, rrect(tx, ty, tw, th, math.floor(th / 2), active and "FFFFFF" or "3A3A3A", active and "00" or (hv and "20" or "50")))
    table.insert(out, txt(tx + math.floor(tw / 2), ty + math.floor(th / 2), 5, math.floor(24 * s), active and "141414" or "FFFFFF", active, label))
    table.insert(hits, { x = tx, y = ty, w = tw, h = th, id = "season" .. sn })
    tx = tx + tw + math.floor(12 * s)
  end

  local list = season_eps()
  local rows = visible_rows(g)
  offset = math.max(0, math.min(offset, #list - rows))
  for i = 1, math.min(rows, #list - offset) do
    local e = list[i + offset]
    local y = g.head + (i - 1) * g.row
    local hv = hover == ("ep" .. e.index)
    local hist = history[norm(e.path)]
    if e.current then
      table.insert(out, rrect(g.x, y, g.pw, g.row, 0, "2A2A2A", "00"))
      table.insert(out, rrect(g.x, y, math.floor(6 * s), g.row, 0, "FF3874", "00"))
    elseif hv then
      table.insert(out, rrect(g.x, y, g.pw, g.row, 0, "FFFFFF", "E8"))
    end
    table.insert(out, txt(x0 + math.floor(26 * s), y + math.floor(g.row / 2), 5, math.floor(40 * s), e.current and "FFFFFF" or "8C8C8C", false, e.episode))
    local tx2 = x0 + math.floor(72 * s)
    local title = e.title ~= "" and e.title or ("Episode " .. e.episode)
    local room = math.floor((g.pw - (tx2 - g.x) - g.pad) / (15 * s))
    table.insert(out, txt(tx2, y + math.floor(40 * s), 4, math.floor(27 * s), "FFFFFF", e.current or hv, fit(title, room)))
    local meta = {}
    if hist and hist.dur and hist.dur > 0 then table.insert(meta, math.floor(hist.dur / 60 + 0.5) .. " min") end
    if e.current then table.insert(meta, "Now playing")
    elseif hist and hist.watched then table.insert(meta, "Watched ✓") end
    if #meta > 0 then table.insert(out, txt(tx2, y + math.floor(76 * s), 4, math.floor(21 * s), e.current and "FFA0B9" or "9A9A9A", false, table.concat(meta, "  ·  "))) end
    -- progress bar for started episodes
    if hist and hist.dur and hist.dur > 0 and hist.pos and hist.pos > 30 and not hist.watched then
      local bw = math.floor(g.pw - (tx2 - g.x) - g.pad)
      local by = y + g.row - math.floor(14 * s)
      table.insert(out, rrect(tx2, by, bw, math.floor(4 * s), 1, "5A5A5A", "00"))
      table.insert(out, rrect(tx2, by, math.max(2, math.floor(bw * math.min(1, hist.pos / hist.dur))), math.floor(4 * s), 1, "FF3874", "00"))
    end
    table.insert(hits, { x = g.x, y = y, w = g.pw, h = g.row, id = "ep" .. e.index })
  end
  if #list > rows then   -- scrollbar
    local th2 = g.h - g.head - g.pad
    local bh = math.max(math.floor(40 * s), th2 * rows / #list)
    local by = g.head + (th2 - bh) * offset / (#list - rows)
    table.insert(out, rrect(g.x + g.pw - math.floor(10 * s), by, math.floor(5 * s), bh, 2, "8C8C8C", "40"))
  end
  ov.res_x, ov.res_y = g.w, g.h
  ov.data = table.concat(out, "\n")
  ov:update()
end

local function hit_at_mouse()
  local m = mp.get_property_native("mouse-pos") or {}
  if not m.x then return nil, false end
  for _, h in ipairs(hits) do
    if m.x >= h.x and m.x <= h.x + h.w and m.y >= h.y and m.y <= h.y + h.h then return h.id, true end
  end
  return nil, m.x >= geo().x
end

local close

local function activate(id)
  if not id then return end
  if id == "close" then close() return end
  local sn = id:match("^season(%d+)$")
  if sn then season = tonumber(sn); offset = 0; render() return end
  local idx = tonumber(id:match("^ep(%d+)$"))
  if idx then
    close()
    if idx ~= mp.get_property_number("playlist-pos") then mp.set_property_number("playlist-pos", idx) end
  end
end

local function current_list_pos()
  local list = season_eps()
  for i, e in ipairs(list) do if hover == ("ep" .. e.index) then return i, list end end
  return nil, list
end

local function move(d)
  local i, list = current_list_pos()
  if #list == 0 then return end
  i = math.max(1, math.min(#list, (i or 0) + d))
  hover = "ep" .. list[i].index
  local rows = visible_rows(geo())
  if i <= offset then offset = i - 1 elseif i > offset + rows then offset = i - rows end
  render()
end

local function switch_season(d)
  for i, sn in ipairs(seasons) do
    if sn == season and seasons[i + d] then season = seasons[i + d]; offset = 0; hover = nil; render() return end
  end
end

local function on_mouse()
  if not open then return end
  local id = hit_at_mouse()   -- only follow the pointer onto rows/buttons, so the keyboard highlight survives
  if id and id ~= hover then hover = id; render() end
end

local KEYS = {
  WHEEL_UP = function() offset = offset - 1; render(); on_mouse() end,
  WHEEL_DOWN = function() offset = offset + 1; render(); on_mouse() end,
  UP = function() move(-1) end, DOWN = function() move(1) end,
  LEFT = function() switch_season(-1) end, RIGHT = function() switch_season(1) end,
  ENTER = function() activate(hover) end, KP_ENTER = function() activate(hover) end,
  ESC = function() close() end,
  MBTN_LEFT = function() local id, inside = hit_at_mouse(); if id then activate(id) elseif not inside then close() end end,
  MBTN_LEFT_DBL = function() end,
}

close = function()
  if not open then return end
  open = false
  mp.set_property_native("user-data/qwik/modal", false)
  for k in pairs(KEYS) do mp.remove_key_binding("episodes_" .. k) end
  mp.unobserve_property(on_mouse)
  mp.commandv("script-message-to", "uosc", "disable-elements", "episodes", "")
  render()
end

local function show()
  collect()
  if #eps == 0 then mp.commandv("script-binding", "uosc/playlist") return end   -- not a series: plain playlist
  load_history()
  season = nil
  for _, e in ipairs(eps) do if e.current then season = e.season end end
  season = season or seasons[1]
  open = true
  mp.set_property_native("user-data/qwik/modal", true)   -- tells Skip Intro / Next Episode to stay out of the way
  local list = season_eps()
  local rows = visible_rows(geo())
  hover, offset = nil, 0
  for i, e in ipairs(list) do if e.current then hover = "ep" .. e.index; offset = math.max(0, i - math.ceil(rows / 2)) end end
  for k, fn in pairs(KEYS) do mp.add_forced_key_binding(k, "episodes_" .. k, fn, { repeatable = true }) end
  mp.observe_property("mouse-pos", "native", on_mouse)
  -- hide the control bar / volume / timeline while the drawer is open (they'd sit on top of it)
  mp.commandv("script-message-to", "uosc", "disable-elements", "episodes", "timeline,controls,volume,top_bar")
  render()
end

mp.add_key_binding(nil, "toggle", function() if open then close() else show() end end)
mp.observe_property("osd-dimensions", "native", function() if open then render() end end)
mp.register_event("file-loaded", function() if open then collect(); render() end end)
