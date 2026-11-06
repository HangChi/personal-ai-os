package com.personalos.task.dto;

import jakarta.validation.constraints.Size;
public record ProjectChange(@Size(max=100) String name,@Size(max=2000) String description,Boolean archived) {}
