export default function handler(req, res) {
  const gnaniConfigured = Boolean(process.env.GNANI_API_KEY);
  res.status(200).json({ gnani: gnaniConfigured });
}
