import React from "react";

const base = { width: 18, height: 18, fill: "none", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

export const MailIcon = ({ color = "#5b48e8" }) => (
  <svg {...base} stroke={color} viewBox="0 0 24 24">
    <path d="M4 4h16v16H4V4zm0 0l8 8 8-8" />
  </svg>
);

export const DocIcon = ({ color = "#0ea5e9" }) => (
  <svg {...base} stroke={color} viewBox="0 0 24 24">
    <path d="M6 2h9l5 5v15H6V2z" />
    <path d="M14 2v6h6" />
    <path d="M9 13h6M9 17h6" />
  </svg>
);

export const ClipboardIcon = ({ color = "#8b5cf6" }) => (
  <svg {...base} stroke={color} viewBox="0 0 24 24">
    <rect x="6" y="4" width="12" height="17" rx="2" />
    <path d="M9 4V2h6v2" />
    <path d="M9 11h6M9 15h6" />
  </svg>
);

export const ClockIcon = ({ color = "#f97316" }) => (
  <svg {...base} stroke={color} viewBox="0 0 24 24">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 3" />
  </svg>
);

export const FlagIcon = ({ color = "#ef4444" }) => (
  <svg {...base} stroke={color} fill={color} viewBox="0 0 24 24">
    <path d="M5 3v18" stroke={color} fill="none" />
    <path d="M5 4h13l-3 4 3 4H5V4z" />
  </svg>
);

export const StarIcon = ({ color = "#eab308" }) => (
  <svg {...base} stroke={color} fill={color} viewBox="0 0 24 24">
    <path d="M12 2l2.9 6.6 7.1.6-5.4 4.7 1.7 7-6.3-3.9L5.7 21l1.7-7L2 9.2l7.1-.6L12 2z" />
  </svg>
);

export const RefreshIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <path d="M21 12a9 9 0 11-3-6.7" />
    <path d="M21 3v6h-6" />
  </svg>
);

export const FilterIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <path d="M4 4h16l-6 8v6l-4 2v-8L4 4z" />
  </svg>
);

export const CalendarIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </svg>
);

export const CheckCircleIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <circle cx="12" cy="12" r="9" />
    <path d="M8.5 12.5l2.5 2.5 5-5" />
  </svg>
);

export const DedupeIcon = ({ color = "#8b5cf6" }) => (
  <svg {...base} stroke={color} viewBox="0 0 24 24">
    <rect x="4" y="4" width="11" height="11" rx="2" />
    <rect x="9" y="9" width="11" height="11" rx="2" />
  </svg>
);

export const XIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <path d="M18 6L6 18M6 6l12 12" />
  </svg>
);

export const ChevronRightIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <path d="M9 6l6 6-6 6" />
  </svg>
);

export const SparkleIcon = ({ color = "#8b5cf6" }) => (
  <svg {...base} stroke={color} fill={color} viewBox="0 0 24 24">
    <path d="M12 2l1.6 5.4L19 9l-5.4 1.6L12 16l-1.6-5.4L5 9l5.4-1.6L12 2z" />
    <path d="M19 15l.7 2.3L22 18l-2.3.7L19 21l-.7-2.3L16 18l2.3-.7L19 15z" />
  </svg>
);

export const EnvelopeOpenIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <path d="M3 8l9 6 9-6" />
    <rect x="3" y="5" width="18" height="14" rx="2" />
  </svg>
);

export const UserIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <circle cx="12" cy="8" r="4" />
    <path d="M4 21c0-4 4-6 8-6s8 2 8 6" />
  </svg>
);

export const GridIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <rect x="3" y="3" width="7" height="7" />
    <rect x="14" y="3" width="7" height="7" />
    <rect x="3" y="14" width="7" height="7" />
    <rect x="14" y="14" width="7" height="7" />
  </svg>
);

export const ListIcon = (props) => (
  <svg {...base} stroke="currentColor" viewBox="0 0 24 24" {...props}>
    <path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" />
  </svg>
);
