package com.capstone.dayj.plan;

import com.capstone.dayj.appUser.AppUser;
import com.capstone.dayj.appUser.AppUserRepository;
import com.capstone.dayj.exception.CustomException;
import com.capstone.dayj.exception.ErrorCode;
import com.capstone.dayj.planOption.PlanOption;
import com.capstone.dayj.planOption.PlanOptionDto;
import com.capstone.dayj.planOption.PlanOptionRepository;
import com.capstone.dayj.tag.Tag;
import com.capstone.dayj.util.ImageUploader;
import com.capstone.dayj.util.KeywordGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanService {
    private final PlanRepository planRepository;
    private final PlanOptionRepository planOptionRepository;
    private final AppUserRepository appUserRepository;
    private final KeywordGenerator keywordGenerator;
    private final ImageUploader imageUploader;
    
    @Transactional
    public PlanDto.Response createPlan(int app_user_id, PlanDto.Request planDto, PlanOptionDto.Request planOptionDto) {
        AppUser findAppUser = appUserRepository.findById(app_user_id)
                .orElseThrow(() -> new CustomException(ErrorCode.APP_USER_NOT_FOUND));
        
        planDto.setAppUser(findAppUser);
        Plan savedPlan = planRepository.save(planDto.toEntity());
        
        planOptionDto.setPlan(savedPlan);
        PlanOption savedPlanOption = planOptionRepository.save(planOptionDto.toEntity());
        
        savedPlan.update(PlanDto.Request.builder()
                .childId(createRepeatedPlan(planDto, planOptionDto))
                .planOption(savedPlanOption)
                .build());
        
        return new PlanDto.Response(savedPlan);
    }
    
    @Transactional(readOnly = true)
    public List<PlanDto.Response> readAllPlanByDate(int app_user_id, LocalDate date) {
        List<Plan> findPlans;
        
        if (date == null) {
            findPlans = planRepository.findAll();
        }
        else {
            findPlans = planRepository.findAllByAppUserId(app_user_id).stream()
                    .filter(plan -> plan.getPlanOption().getPlanStartTime().toLocalDate().equals(date))
                    .toList();
        }
        
        if (findPlans.isEmpty())
            throw new CustomException(ErrorCode.PLAN_NOT_FOUND);
        
        return findPlans.stream().map(PlanDto.Response::new).collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<PlanDto.Response> readAllPlanByPlanTag(int app_user_id, Tag plan_tag, LocalDate date) {
        List<Plan> findPlans = planRepository.findAllByAppUserIdAndPlanTag(app_user_id, plan_tag).stream()
                .filter(plan -> plan.getPlanOption().getPlanStartTime().toLocalDate().equals(date))
                .toList();
        
        if (findPlans.isEmpty())
            throw new CustomException(ErrorCode.PLAN_NOT_FOUND);
        
        return findPlans.stream().map(PlanDto.Response::new).collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public PlanDto.Response readPlanById(int plan_id) {
        Plan findPlan = planRepository.findById(plan_id)
                .orElseThrow(() -> new CustomException(ErrorCode.PLAN_NOT_FOUND));
        
        
        return new PlanDto.Response(findPlan);
    }
    
    @Transactional
    public Set<String> reminderPlan(int app_user_id, Tag tag) {
        List<Plan> findPlans = new ArrayList<>(planRepository.findAllByAppUserId(app_user_id).stream()
                .filter(plan -> plan.getPlanTag().equals(tag)).toList());
        Set<String> recommendGoal = new HashSet<>(Set.of());
        
        if (keywordGenerator.getKeywords().isEmpty()) {
            throw new CustomException(ErrorCode.KEYWORD_NOT_FOUND);
        }
        
        if (findPlans.isEmpty()) {
            throw new CustomException(ErrorCode.PLAN_NOT_FOUND);
        }
        
        Collections.shuffle(findPlans);
        keywordGenerator.getKeywords().get(tag)
                .forEach(keyword -> {
                    findPlans.forEach(plan -> {
                        if (recommendGoal.size() >= 3) return;
                        if (plan.getGoal().contains(keyword))
                            recommendGoal.add(plan.getGoal());
                    });
                });
        
        findPlans.forEach(plan -> {
            if (recommendGoal.size() >= 5) return;
            recommendGoal.add(plan.getGoal());
        });
        
        return recommendGoal;
    }
    
    @Transactional
    public PlanDto.Response patchPlan(int plan_id, PlanDto.Request planDto, PlanOptionDto.Request planOptionDto) {
        Plan findPlan = planRepository.findById(plan_id)
                .orElseThrow(() -> new CustomException(ErrorCode.PLAN_NOT_FOUND));
        
        // 기존 plan에 반복 조건이 있던 경우
        if (findPlan.getChildId() != null && !findPlan.getChildId().isEmpty()) {
            LocalDate beforeRepeatStartDate = findPlan.getPlanOption()
                    .getPlanRepeatStartDate()
                    .toLocalDate();
            LocalDate beforeRepeatEndDate = findPlan.getPlanOption()
                    .getPlanRepeatEndDate()
                    .toLocalDate();
            List<DayOfWeek> beforeDaysOfWeek = findPlan.getPlanOption()
                    .getPlanDaysOfWeek();
            
            // 반복 조건 유지하고 PLAN만 수정하는 경우 -> 자식 계획 부모와 동일하게 수정
            findPlan.getChildId().removeIf(childId -> {
                Optional<Plan> childPlan = planRepository.findById(childId);
                childPlan.ifPresent(plan -> plan.update(planDto));
                return childPlan.isEmpty(); // childId에 해당하는 PLAN이 없으면 childId 리스트에서 제거
            });
            
            // 반복 조건 삭제하는 경우
            if (planOptionDto.getPlanRepeatStartDate() == null) {
                findPlan.getChildId().forEach(planRepository::deleteById);
                findPlan.getChildId().clear();
            }
            
            // 반복 조건을 변경하는 경우 -> 기존 자식 plan들 삭제 후 새로 생성
            else if (!planOptionDto.getPlanRepeatStartDate().toLocalDate().equals(beforeRepeatStartDate)
                    || !planOptionDto.getPlanRepeatEndDate().toLocalDate().equals(beforeRepeatEndDate)
                    || !planOptionDto.getPlanDaysOfWeek().equals(beforeDaysOfWeek)) {
                findPlan.getChildId().forEach(planRepository::deleteById);
                findPlan.update(PlanDto.Request.builder()
                        .childId(createRepeatedPlan(planDto, planOptionDto))
                        .build());
            }
        }
        
        else {
            findPlan.update(PlanDto.Request.builder()
                    .childId(createRepeatedPlan(planDto, planOptionDto))
                    .build());
        }
        
        findPlan.getPlanOption().update(planOptionDto);
        findPlan.update(planDto);
        
        return new PlanDto.Response(findPlan);
    }
    
    @Transactional
    public PlanDto.Response patchPlanImage(int plan_id, MultipartFile image) throws IOException {
        if (image == null || image.isEmpty()) {
            throw new CustomException(ErrorCode.IMAGE_UPLOAD_FAIL);
        }
        
        Plan findPlan = planRepository.findById(plan_id)
                .orElseThrow(() -> new CustomException(ErrorCode.PLAN_NOT_FOUND));
        
        PlanDto.Request dto = new PlanDto.Request();
        dto.setPlanPhoto(imageUploader.upload(image));
        findPlan.update(dto);
        return new PlanDto.Response(findPlan);
    }
    
    @Transactional
    public String deletePlanById(int plan_id) {
        Plan findPlan = planRepository.findById(plan_id)
                .orElseThrow(() -> new CustomException(ErrorCode.PLAN_NOT_FOUND));
        
        // 부모 plan 삭제 시, 자식 plan 함께 삭제
        if ((!findPlan.getChildId().isEmpty())) {
            findPlan.getChildId().forEach(planRepository::deleteById);
        }
        
        planRepository.delete(findPlan);
        
        return String.format("Plan(id: %d) was Deleted", findPlan.getId());
    }
    
    @Transactional
    public List<Integer> createRepeatedPlan(PlanDto.Request planDto, PlanOptionDto.Request planOptionDto) {
        List<Integer> childIds = new ArrayList<>();
        
        if (planOptionDto.getPlanRepeatStartDate() != null) {
            LocalDate startDate = planOptionDto.getPlanRepeatStartDate().toLocalDate();
            LocalDate endDate = planOptionDto.getPlanRepeatEndDate().toLocalDate();
            if (startDate.isAfter(endDate)) throw new CustomException(ErrorCode.DATE_RANGE_ERROR);
            
            
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                if (planOptionDto.getPlanDaysOfWeek().contains(date.getDayOfWeek())) {
                    int hour = 0;
                    if(planOptionDto.getPlanStartTime() != null ){
                        hour = planOptionDto.getPlanStartTime().getHour();
                    }

                    PlanDto.Request newPlanDto = PlanDto.Request.builder()
                            .appUser(planDto.getAppUser())
                            .planTag(planDto.getPlanTag())
                            .goal(planDto.getGoal())
                            .isPublic(planDto.getIsPublic())
                            .build();
                    
                    Plan savedPlan = planRepository.save(newPlanDto.toEntity());

                    PlanOptionDto.Request newPlanOptionDto = PlanOptionDto.Request.builder()
                            .plan(savedPlan)
                            .planStartTime(date.atTime(hour, 0, 0))
                            .planEndTime(date.atTime(hour + 1, 0, 0))
                            .build();

                    PlanOption savedPlanOption = planOptionRepository.save(newPlanOptionDto.toEntity());
                    
                    savedPlan.update(PlanDto.Request.builder()
                            .planOption(savedPlanOption)
                            .build());
                    
                    childIds.add(savedPlan.getId());
                }
            }
        }
        return childIds;
    }
}
