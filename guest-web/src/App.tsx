import { useState } from "react";
import { DentalApp } from "@/dental/DentalApp";
import HotelApp from "@/HotelApp";
import { claimLink } from "@/lib/token";

/** Picks the flow from the link: `#d/<token>` is the dental pre-visit form, anything else the hotel check-in. */
export default function App() {
  const [link] = useState(() => claimLink(window.location));
  // A reload drops the fragment, so there is no link: the clinic's host still gets the dental "invalid" page.
  const dental = link ? link.kind === "dental" : window.location.hostname.startsWith("nhakhoa.");
  return dental ? <DentalApp /> : <HotelApp />;
}
