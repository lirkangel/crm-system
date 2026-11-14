import { useState } from "react";
import { DentalApp } from "@/dental/DentalApp";
import HotelApp from "@/HotelApp";
import { claimLink } from "@/lib/token";

/** Picks the flow from the link: `#d/<token>` is the dental pre-visit form, anything else the hotel check-in. */
export default function App() {
  const [link] = useState(() => claimLink(window.location));
  return link?.kind === "dental" ? <DentalApp /> : <HotelApp />;
}
