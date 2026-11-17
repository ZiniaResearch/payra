import axios from "axios";

const baseURL = import.meta.env.VITE_API_URL;

if (!baseURL) {
	throw new Error("VITE_API_URL is required.");
}

export const api = axios.create({
	baseURL,
	withCredentials: true,
	timeout: 15_000,
});
