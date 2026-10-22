function update(frame)
    marine.uniform("warmth", 0.12 + 0.03 * math.sin(frame.day * 6.283185307))
    marine.uniform("bloom", frame.quality == "low" and 0 or 0.18)
end
