-- Netflix-style "Next Episode" button: appears in the last 45 s of an episode (when the playlist has
-- a next one), counts down to the automatic switch, and plays the next episode when clicked.
-- Ctrl+N (script-binding next_episode/go) does the same at any time.

local SHOW_BEFORE = 45          -- seconds before the end
local ov = mp.create_osd_overlay("ass-events")
local visible, box, hovering = false, nil, false

local function next_entry()
  local list = mp.get_property_native("playlist") or {}
  local pos = mp.get_property_number("playlist-pos", -1)
  return list[pos + 2], pos
end

-- "The Americans S01E08 Mutually Assured Destruction.mkv" -> "S01E08 · Mutually Assured Destruction"
local function label(file)
  local name = (file or ""):match("([^/\\]+)$") or ""
  name = name:gsub("%.%w+$", ""):gsub("[%._]", " ")
  local s, e, rest = name:match("[Ss](%d+)%s*[Ee](%d+)(.*)$")
  if not s then return name end
  rest = rest:gsub("%f[%w]%d%d%d%d?p%f[%W].*$", ""):gsub("[%[%(].*$", ""):gsub("^[%s%-]+", ""):gsub("[%s%-]+$", "")
  return string.format("S%02dE%02d", tonumber(s), tonumber(e)) .. (rest ~= "" and ("  ·  " .. rest) or "")
end

local function rrect(x, y, w, h, r, color, alpha)
  return string.format("{\\an7\\pos(%d,%d)\\bord0\\shad0\\1c&H%s&\\1a&H%s&\\p1}m %d 0 l %d 0 b %d 0 %d 0 %d %d l %d %d b %d %d %d %d %d %d l %d %d b 0 %d 0 %d 0 %d l 0 %d b 0 0 0 0 %d 0{\\p0}",
    x, y, color, alpha, r, w - r, w, w, w, r, w, h - r, w, h, w, h, w - r, h, r, h, h, h, h - r, r, r)
end

local function hide()
  if not visible then return end
  visible, box, hovering = false, nil, false
  mp.remove_key_binding("next_episode_click")
  ov:remove()
end

local function go()
  local nxt = next_entry()
  if not nxt then mp.osd_message("This is the last episode") return end
  hide()
  mp.commandv("playlist-next", "force")
end

local function draw(left)
  local nxt = next_entry()
  local w, h = mp.get_osd_size()
  if not w or w == 0 then return end
  local s = h / 1080
  local bw, bh = math.floor(360 * s), math.floor(92 * s)
  local x, y = math.floor(w - bw - 48 * s), math.floor(h - bh - 190 * s)
  box = { x = x, y = y, w = bw, h = bh }
  local p = math.max(0, math.min(1, 1 - left / SHOW_BEFORE))
  local fs = math.floor(34 * s)
  local out = {
    rrect(x, y + math.floor(8 * s), bw, bh, math.floor(10 * s), "000000", "70"),                     -- shadow
    rrect(x, y, bw, bh, math.floor(10 * s), hovering and "FFFFFF" or "E6E6E6", "00"),               -- white button
    rrect(x, y + bh - math.floor(6 * s), math.floor(bw * p), math.floor(6 * s), 1, "1409E5", "00"), -- countdown bar (red)
    string.format("{\\an4\\pos(%d,%d)\\fnPoppins\\b1\\fs%d\\bord0\\shad0\\1c&H141414&}▶  Next Episode", x + math.floor(26 * s), y + math.floor(32 * s), fs),
    string.format("{\\an4\\pos(%d,%d)\\fnPoppins\\fs%d\\bord0\\shad0\\1c&H555555&}%s", x + math.floor(26 * s), y + math.floor(64 * s), math.floor(24 * s),
      (label(nxt and nxt.filename):sub(1, 38)) .. string.format("   ·   %ds", math.max(0, math.floor(left)))),
  }
  ov.res_x, ov.res_y = w, h
  ov.data = table.concat(out, "\n")
  ov:update()
end

local function inside()
  if not box then return false end
  local m = mp.get_property_native("mouse-pos") or {}
  return m.x and m.x >= box.x and m.x <= box.x + box.w and m.y >= box.y and m.y <= box.y + box.h
end

local function tick()
  local dur = mp.get_property_number("duration")
  local pos = mp.get_property_number("time-pos")
  local nxt = next_entry()
  if not dur or not pos or not nxt or dur < 300 then hide() return end
  local left = dur - pos
  if left > SHOW_BEFORE or left < 0 then hide() return end
  if not visible then
    visible = true
    -- Clicks only go to the button while the pointer is on it; elsewhere the video keeps its usual click.
  end
  local over = inside()
  if over ~= hovering then
    hovering = over
    if over then mp.add_forced_key_binding("MBTN_LEFT", "next_episode_click", go)
    else mp.remove_key_binding("next_episode_click") end
  end
  draw(left)
end

mp.add_periodic_timer(0.25, tick)
mp.observe_property("mouse-pos", "native", function() if visible then tick() end end)
mp.register_event("file-loaded", hide)
mp.add_key_binding(nil, "go", go)
