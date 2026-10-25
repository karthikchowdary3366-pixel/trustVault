package com.example.trustvault.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice
public class WebErrorHandler {
	@ExceptionHandler(ResponseStatusException.class)
	public ModelAndView handleStatus(ResponseStatusException exception) {
		HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
		ModelAndView response = new ModelAndView("error");
		response.addObject("status", status.value());
		response.addObject("message", exception.getReason() == null ? status.getReasonPhrase() : exception.getReason());
		response.setStatus(status);
		return response;
	}
}
