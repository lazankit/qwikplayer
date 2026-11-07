-- Netflix-style "Skip Intro" button. Intro and credits times come from the player (Intros.cs compares the
-- audio of neighbouring episodes) via %LOCALAPPDATA%\Oranix\Player\intros.json, or from chapters named
-- Intro/Opening/OP. Click the button or press Enter while it shows.
-- Also publishes user-data/oranix/credits (start of the end credits) for next-episode.lua.

local utils = require "mp.utils"
local ov = mp.create_osd_overlay("ass-events")
local info, visible, hovering, box = nil, false, false, nil
local FILE = (os.getenv("LOCALAPPDATA") or "") .. "\\Oranix\\Player\\intros.json"

local function norm(p) return (p or ""):gsub("/", "\\"):lower() end

local function from_chapters()
  local list = mp.get_property_native("chapter-list") or {}
  for i, c in ipairs(list) do
    local t = (c.title or ""):lower()
    if t:find("intro") or t:find("opening") or t == "op" or t:find("^op%s") then
      local nxt = list[i + 1]
      if nxt then return { introStart = c.time, introEnd = nxt.time } end
    end
  end
end

local function load()
  info = from_chapters()
  local f = io.open(FILE, "r")
  if f then
    local data = utils.parse_json(f:read("*a") or "") or {}
    f:close()
    local me = norm(mp.get_property("path"))
    for k, v in pairs(data) do
      if norm(k) == me then
        info = info or {}
        if not info.introStart and v.introStart and v.introStart >= 0 then info.introStart, info.introEnd = v.introStart, v.introEnd end
        if v.creditsStart and v.creditsStart > 0 then info.creditsStart = v.creditsStart end
      end
    end
  end
  mp.set_property_native("user-data/oranix/credits", info and info.creditsStart or -1)
end

local function rrect(x, y, w, h, r, color, alpha, extra)
  return string.format("{\\an7\\pos(%d,%d)\\bord0\\shad0%s\\1c&H%s&\\1a&H%s&\\p1}m %d 0 l %d 0 b %d 0 %d 0 %d %d l %d %d b %d %d %d %d %d %d l %d %d b 0 %d 0 %d 0 %d l 0 %d b 0 0 0 0 %d 0{\\p0}",
    x, y, extra or "", color, alpha, r, w - r, w, w, w, r, w, h - r, w, h, w, h, w - r, h, r, h, h, h, h - r, r, r)
end

local function hide()
  if not visible then return end
  visible, hovering, box = false, false, nil
  mp.remove_key_binding("skip_intro_click")
  mp.remove_key_binding("skip_intro_enter")
  ov:remove()
end

local function skip()
  if not info or not info.introEnd then return end
  hide()
  mp.commandv("seek", tostring(info.introEnd), "absolute+exact")
  mp.osd_message("Intro skipped", 1)
end

local function draw()
  local w, h = mp.get_osd_size()
  if not w or w == 0 then return end
  local s = h / 1080
  local bw, bh = math.floor(300 * s), math.floor(90 * s)
  local x, y = math.floor(w - bw - 48 * s), math.floor(h - bh - 190 * s)
  box = { x = x, y = y, w = bw, h = bh }
  ov.res_x, ov.res_y = w, h
  ov.data = table.concat({
    -- Netflix look: dark glass with a white outline; fills white on hover
    rrect(x, y, bw, bh, math.floor(8 * s), hovering and "FFFFFF" or "1A1A1A", hovering and "00" or "40",
      "\\bord" .. math.max(1, math.floor(3 * s)) .. "\\3c&HFFFFFF&\\3a&H00&"),
    string.format("{\\an5\\pos(%d,%d)\\fnPoppins\\b1\\fs%d\\bord0\\shad0\\1c&H%s&}Skip Intro", x + math.floor(bw / 2), y + math.floor(bh / 2),
      math.floor(36 * s), hovering and "141414" or "FFFFFF"),
  }, "\n")
  ov:update()
end

local function inside()
  if not box then return false end
  local m = mp.get_property_native("mouse-pos") or {}
  return m.x and m.x >= box.x and m.x <= box.x + box.w and m.y >= box.y and m.y <= box.y + box.h
end

local function tick()
  local t = mp.get_property_number("time-pos")
  if not info or not info.introStart or not t or t < info.introStart - 0.5 or t > info.introEnd - 2 then hide() return end
  if not visible then
    visible = true
    mp.add_forced_key_binding("ENTER", "skip_intro_enter", skip)
  end
  local over = inside()
  if over ~= hovering then
    hovering = over
    if over then mp.add_forced_key_binding("MBTN_LEFT", "skip_intro_click", skip)
    else mp.remove_key_binding("skip_intro_click") end
  end
  draw()
end

mp.register_event("file-loaded", function() hide(); load() end)
mp.register_script_message("intros-updated", load)
mp.add_periodic_timer(0.25, tick)
mp.observe_property("mouse-pos", "native", function() if visible then tick() end end)
mp.add_key_binding(nil, "skip", skip)
